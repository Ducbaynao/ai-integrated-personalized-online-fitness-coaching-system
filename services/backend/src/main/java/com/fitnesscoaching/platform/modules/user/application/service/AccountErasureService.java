package com.fitnesscoaching.platform.modules.user.application.service;

import com.fitnesscoaching.platform.modules.auth.application.port.in.AccountErasureSessionCutoff;
import com.fitnesscoaching.platform.modules.audit.AuditAccountErasure;
import com.fitnesscoaching.platform.modules.chat.ChatAccountErasure;
import com.fitnesscoaching.platform.modules.notification.AccountNotificationErasure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Account-owned request and status. Chat, Auth and Notification effects use owner ports. */
@Service
public class AccountErasureService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountErasureSessionCutoff sessions;
    private final AccountNotificationErasure notifications;
    private final ChatAccountErasure chat;
    private final AuditAccountErasure audit;
    private final boolean requestEnabled;

    public AccountErasureService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                 AccountErasureSessionCutoff sessions,
                                 AccountNotificationErasure notifications, ChatAccountErasure chat,
                                 AuditAccountErasure audit,
                                 @Value("${account.erasure.request-enabled:false}") boolean requestEnabled) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.sessions = sessions;
        this.notifications = notifications;
        this.chat = chat;
        this.audit = audit;
        this.requestEnabled = requestEnabled;
    }

    public record State(UUID id, String status, Instant requestedAt, Instant targetAt,
                        Instant activeCleanedAt, String backupState, String providerState) {}
    private record Existing(UUID id, UUID commandKey, String statusKeyHash) {}

    public State request(UUID actor, UUID commandKey, String statusKey) {
        // Other domain-owned PII must be covered before opening this high-impact command.
        if (!requestEnabled) throw new Failure("ACCOUNT_ERASURE_UNAVAILABLE", 503);
        if (actor == null || commandKey == null || !validStatusKey(statusKey))
            throw new Failure("ACCOUNT_ERASURE_INVALID_REQUEST", 400);
        String keyHash = sha256(statusKey);
        return transactions.execute(ignored -> {
            List<String> statuses = jdbc.query("SELECT status::text FROM fitness.users WHERE id=? FOR NO KEY UPDATE",
                    (rs, row) -> rs.getString(1), actor);
            if (statuses.isEmpty()) throw new Failure("ACCOUNT_ERASURE_NOT_FOUND", 404);
            List<Existing> previous = jdbc.query("""
                    SELECT id,command_key,status_key_hash FROM fitness.account_erasure_requests WHERE user_id=?
                    """, (rs, row) -> new Existing(rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class), rs.getString(3).trim()), actor);
            if (!previous.isEmpty()) {
                Existing prior = previous.getFirst();
                if (!prior.commandKey().equals(commandKey) || !constantTimeEqual(prior.statusKeyHash(), keyHash))
                    throw new Failure("ACCOUNT_ERASURE_IDEMPOTENCY_CONFLICT", 409);
                return load(prior.id());
            }
            if (!"ACTIVE".equals(statuses.getFirst())) throw new Failure("ACCOUNT_ERASURE_NOT_FOUND", 404);
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.account_erasure_requests(id,user_id,command_key,status_key_hash,target_at)
                    VALUES (?,?,?,?,clock_timestamp()+interval '24 hours')
                    """, id, actor, commandKey, keyHash);
            int changed = jdbc.update("""
                    UPDATE fitness.users SET status='DISABLED',updated_at=clock_timestamp()
                    WHERE id=? AND status='ACTIVE'
                    """, actor);
            if (changed != 1) throw new Failure("ACCOUNT_ERASURE_NOT_FOUND", 404);
            Instant at = jdbc.queryForObject("SELECT clock_timestamp()", Instant.class);
            sessions.revokeForErasure(actor, at);
            notifications.revokePushAndHideInbox(actor);
            for (var object : chat.concealAndListSentObjects(actor)) {
                boolean ownedS3 = object.ownerVerified() && "S3".equals(object.storageProvider())
                        && object.bucket() != null;
                String state = !object.ownerVerified() || object.bucket() == null
                        ? "OWNER_REVIEW_REQUIRED" : ownedS3 ? "PENDING" : "STORAGE_REVIEW_REQUIRED";
                jdbc.update("""
                        INSERT INTO fitness.account_erasure_objects
                            (request_id,media_id,bucket_name,object_key,status,last_error_code)
                        VALUES (?,?,?,?,?,?) ON CONFLICT (request_id,media_id) DO NOTHING
                        """, id, object.mediaId(), object.bucket(), object.key(), state,
                        "STORAGE_REVIEW_REQUIRED".equals(state) ? "STORAGE_PROVIDER_UNSUPPORTED" : null);
            }
            jdbc.update("""
                    INSERT INTO fitness.user_account_status_history(user_id,from_status,to_status,changed_by,reason)
                    VALUES (?,'ACTIVE','DISABLED',?,'ACCOUNT_ERASURE_REQUESTED')
                    """, actor, actor);
            event(id, "REQUESTED", null);
            return load(id);
        });
    }

    /** Opaque 256-bit capability permits only progress read after bearer tokens are cut off. */
    public State status(UUID id, String statusKey) {
        if (id == null || !validStatusKey(statusKey)) throw new Failure("ACCOUNT_ERASURE_NOT_FOUND", 404);
        List<String> hashes = jdbc.query("SELECT status_key_hash FROM fitness.account_erasure_requests WHERE id=?",
                (rs, row) -> rs.getString(1).trim(), id);
        if (hashes.isEmpty() || !constantTimeEqual(hashes.getFirst(), sha256(statusKey)))
            throw new Failure("ACCOUNT_ERASURE_NOT_FOUND", 404);
        return load(id);
    }

    public void recordProgress(UUID id, String status, String error) {
        transactions.executeWithoutResult(ignored -> {
            jdbc.update("""
                    UPDATE fitness.account_erasure_requests SET status=?,last_error_code=?,updated_at=clock_timestamp()
                    WHERE id=? AND status<>'ACTIVE_DATA_CLEANED'
                    """, status, error, id);
            event(id, status, error);
        });
    }

    /** null means a newer worker owns the request lease; never commit a stale finalization. */
    public Boolean minimizeCurrentScope(UUID id, UUID userId, UUID claimToken) {
        return transactions.execute(ignored -> {
            List<UUID> owners = jdbc.query("""
                    SELECT lease_token FROM fitness.account_erasure_requests WHERE id=? FOR UPDATE
                    """, (rs, row) -> rs.getObject(1, UUID.class), id);
            if (owners.isEmpty() || !claimToken.equals(owners.getFirst())) return null;
            List<Integer> holds = jdbc.query("""
                    SELECT 1 FROM fitness.account_erasure_holds
                    WHERE request_id=? AND released_at IS NULL LIMIT 1
                    """, (rs, row) -> rs.getInt(1), id);
            if (!holds.isEmpty()) {
                jdbc.update("UPDATE fitness.account_erasure_requests SET status='HELD',updated_at=clock_timestamp() WHERE id=?", id);
                event(id, "HELD", null);
                return false;
            }
            chat.redactSentMessages(userId);
            notifications.minimizeAfterCleanup(userId, id);
            audit.redactForAccount(userId, id);
            // Preserve the stable UUID and FK-linked history; remove direct account identifiers.
            jdbc.update("""
                    UPDATE fitness.users SET email=('erased-'||id::text||'@invalid.local'),
                      display_name='Tài khoản đã xóa',phone_number=NULL,
                      password_hash='ERASED:'||id::text,deleted_at=COALESCE(deleted_at,clock_timestamp()),
                      updated_at=clock_timestamp() WHERE id=? AND status='DISABLED'
                    """, userId);
            jdbc.update("""
                    UPDATE fitness.user_settings SET accessibility_preferences='{}'::jsonb,
                      privacy_preferences='{}'::jsonb WHERE user_id=?
                    """, userId);
            jdbc.update("DELETE FROM fitness.auth_identities WHERE user_id=?", userId);
            jdbc.update("DELETE FROM fitness.one_time_tokens WHERE user_id=?", userId);
            jdbc.update("UPDATE fitness.user_devices SET push_token=NULL,device_identifier=NULL WHERE user_id=?", userId);
            jdbc.update("""
                    UPDATE fitness.account_erasure_requests SET status='DOMAIN_REVIEW_PENDING',
                      updated_at=clock_timestamp(),last_error_code=NULL
                    WHERE id=? AND status<>'ACTIVE_DATA_CLEANED'
                    """, id);
            event(id, "ACCOUNT_CHAT_NOTIFICATION_MINIMIZED", null);
            return true;
        });
    }

    private State load(UUID id) {
        return jdbc.queryForObject("""
                SELECT id,status,requested_at,target_at,active_cleaned_at,backup_state,provider_state
                FROM fitness.account_erasure_requests WHERE id=?
                """, (rs, row) -> new State(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(),
                rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                rs.getString(6), rs.getString(7)), id);
    }

    private void event(UUID id, String type, String error) {
        jdbc.update("INSERT INTO fitness.account_erasure_events(request_id,event_type,error_code) VALUES (?,?,?)",
                id, type, error);
    }

    private static boolean validStatusKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) return false;
        try { return Base64.getUrlDecoder().decode(value).length == 32; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean constantTimeEqual(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    public static final class Failure extends RuntimeException {
        public final String code;
        public final int status;
        public Failure(String code, int status) { super(code); this.code = code; this.status = status; }
    }
}
