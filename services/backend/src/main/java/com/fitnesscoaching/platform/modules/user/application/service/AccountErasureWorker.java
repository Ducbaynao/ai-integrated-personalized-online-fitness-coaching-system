package com.fitnesscoaching.platform.modules.user.application.service;

import com.fitnesscoaching.platform.modules.chat.ChatObjectStorage;
import com.fitnesscoaching.platform.modules.chat.ChatFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/** Lease/fencing and short DB transactions; S3 I/O never runs with a DB transaction or row lock. */
@Component
public class AccountErasureWorker {
    private static final Logger log = LoggerFactory.getLogger(AccountErasureWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountErasureService accounts;
    private final ChatObjectStorage storage;
    private final boolean enabled;

    public AccountErasureWorker(JdbcTemplate jdbc, PlatformTransactionManager manager,
            AccountErasureService accounts, ChatObjectStorage storage,
            @Value("${account.erasure.worker-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.accounts = accounts;
        this.storage = storage;
        this.enabled = enabled;
    }

    private record RequestClaim(UUID id, UUID userId, UUID token) {}
    private record ObjectClaim(UUID id, String bucket, String key, UUID token) {}

    @Scheduled(fixedDelayString = "${account.erasure.worker-delay-ms:60000}")
    public void scheduled() { if (enabled) processOne(); }

    public boolean processOne() {
        RequestClaim request = transactions.execute(ignored -> {
            List<RequestClaim> due = jdbc.query("""
                    SELECT r.id,r.user_id FROM fitness.account_erasure_requests r
                    WHERE r.status IN ('REQUESTED','PROCESSING','RETRYABLE','HELD')
                      AND r.next_attempt_at <= clock_timestamp()
                      AND (r.lease_expires_at IS NULL OR r.lease_expires_at < clock_timestamp())
                      AND (r.status <> 'HELD' OR NOT EXISTS
                          (SELECT 1 FROM fitness.account_erasure_holds h
                           WHERE h.request_id=r.id AND h.released_at IS NULL))
                    ORDER BY r.requested_at,r.id FOR UPDATE OF r SKIP LOCKED LIMIT 1
                    """, (rs, row) -> new RequestClaim(rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class), UUID.randomUUID()));
            if (due.isEmpty()) return null;
            RequestClaim claim = due.getFirst();
            jdbc.update("""
                    UPDATE fitness.account_erasure_requests SET status='PROCESSING',lease_token=?,
                      lease_expires_at=clock_timestamp()+interval '5 minutes',
                      attempt_count=attempt_count+1,updated_at=clock_timestamp()
                    WHERE id=?
                    """, claim.token(), claim.id());
            return claim;
        });
        if (request == null) return false;
        try {
            if (holdActive(request.id())) {
                release(request, "HELD", null);
                return true;
            }
            while (true) {
                if (holdActive(request.id())) {
                    release(request, "HELD", null);
                    return true;
                }
                ObjectClaim object = claimObject(request);
                if (object == null) break;
                try {
                    storage.deleteAndVerify(object.bucket(), object.key());
                    transactions.executeWithoutResult(ignored -> {
                        int changed = jdbc.update("""
                                UPDATE fitness.account_erasure_objects SET status='VERIFIED',claim_token=NULL,
                                  lease_expires_at=NULL,verified_at=clock_timestamp(),
                                  tombstone_until=clock_timestamp()+interval '30 days',last_error_code=NULL
                                WHERE id=? AND status='CLAIMED' AND claim_token=?
                                """, object.id(), object.token());
                        if (changed == 1) jdbc.update("""
                                INSERT INTO fitness.account_erasure_events(request_id,event_type,object_id)
                                VALUES (?,'OBJECT_VERIFIED',?)
                                """, request.id(), object.id());
                    });
                } catch (RuntimeException unavailable) {
                    log.warn("Account erasure object delete unverified for request {}", request.id(), unavailable);
                    if (unavailable instanceof ChatFailure chatFailure
                            && "CHAT_ATTACHMENT_ERASURE_TOPOLOGY_UNVERIFIED".equals(chatFailure.code())) {
                        transactions.executeWithoutResult(ignored -> jdbc.update("""
                                UPDATE fitness.account_erasure_objects
                                SET status='STORAGE_REVIEW_REQUIRED',claim_token=NULL,lease_expires_at=NULL,
                                    last_error_code='STORAGE_TOPOLOGY_UNVERIFIED'
                                WHERE id=? AND claim_token=?
                                """, object.id(), object.token()));
                        release(request, "DOMAIN_REVIEW_PENDING", "STORAGE_TOPOLOGY_UNVERIFIED");
                        return true;
                    }
                    transactions.executeWithoutResult(ignored -> jdbc.update("""
                            UPDATE fitness.account_erasure_objects SET status='RETRYABLE',claim_token=NULL,
                              lease_expires_at=NULL,next_attempt_at=clock_timestamp()+interval '1 minute',
                              last_error_code='OBJECT_DELETE_UNVERIFIED'
                            WHERE id=? AND claim_token=?
                            """, object.id(), object.token()));
                    fail(request, "OBJECT_DELETE_UNVERIFIED");
                    return true;
                }
            }
            boolean pending = Boolean.TRUE.equals(transactions.execute(ignored -> jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM fitness.account_erasure_objects
                                  WHERE request_id=? AND status<>'VERIFIED')
                    """, Boolean.class, request.id())));
            if (pending) {
                boolean ambiguous = Boolean.TRUE.equals(transactions.execute(ignored -> jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM fitness.account_erasure_objects
                                      WHERE request_id=? AND status IN
                                          ('OWNER_REVIEW_REQUIRED','STORAGE_REVIEW_REQUIRED'))
                        """, Boolean.class, request.id())));
                boolean storageReview = ambiguous && Boolean.TRUE.equals(transactions.execute(ignored ->
                        jdbc.queryForObject("""
                                SELECT EXISTS(SELECT 1 FROM fitness.account_erasure_objects
                                              WHERE request_id=? AND status='STORAGE_REVIEW_REQUIRED')
                                """, Boolean.class, request.id())));
                boolean unsupportedProvider = storageReview && Boolean.TRUE.equals(transactions.execute(ignored ->
                        jdbc.queryForObject("""
                                SELECT EXISTS(SELECT 1 FROM fitness.account_erasure_objects
                                              WHERE request_id=? AND status='STORAGE_REVIEW_REQUIRED'
                                                AND last_error_code='STORAGE_PROVIDER_UNSUPPORTED')
                                """, Boolean.class, request.id())));
                release(request, ambiguous ? "DOMAIN_REVIEW_PENDING" : "PROCESSING",
                        unsupportedProvider ? "STORAGE_PROVIDER_UNSUPPORTED"
                                : storageReview ? "STORAGE_TOPOLOGY_UNVERIFIED"
                                : ambiguous ? "LEGACY_MEDIA_OWNER_UNVERIFIED" : null);
                return true;
            }
            Boolean minimized = accounts.minimizeCurrentScope(request.id(), request.userId(), request.token());
            if (minimized == null) return true;
            // Other domain-owned PII still requires inventory/ports. Never claim all active data is clean.
            release(request, minimized ? "DOMAIN_REVIEW_PENDING" : "HELD", null);
        } catch (RuntimeException failure) {
            log.warn("Account erasure active cleanup retryable for request {}", request.id(), failure);
            fail(request, "ACCOUNT_CLEANUP_RETRYABLE");
        }
        return true;
    }

    private boolean holdActive(UUID requestId) {
        return Boolean.TRUE.equals(transactions.execute(ignored -> jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.account_erasure_holds
                              WHERE request_id=? AND released_at IS NULL)
                """, Boolean.class, requestId)));
    }

    private ObjectClaim claimObject(RequestClaim request) {
        return transactions.execute(ignored -> {
            List<ObjectClaim> due = jdbc.query("""
                    SELECT id,bucket_name,object_key FROM fitness.account_erasure_objects
                    WHERE request_id=? AND status IN ('PENDING','RETRYABLE','CLAIMED')
                      AND next_attempt_at <= clock_timestamp()
                      AND (lease_expires_at IS NULL OR lease_expires_at < clock_timestamp())
                    ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1
                    """, (rs, row) -> new ObjectClaim(rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getString(3), UUID.randomUUID()), request.id());
            if (due.isEmpty()) return null;
            ObjectClaim claim = due.getFirst();
            jdbc.update("""
                    UPDATE fitness.account_erasure_objects SET status='CLAIMED',claim_token=?,
                      lease_expires_at=clock_timestamp()+interval '5 minutes',
                      attempt_count=attempt_count+1 WHERE id=?
                    """, claim.token(), claim.id());
            return claim;
        });
    }

    private void fail(RequestClaim claim, String error) {
        transactions.executeWithoutResult(ignored -> jdbc.update("""
                UPDATE fitness.account_erasure_requests SET status='RETRYABLE',lease_token=NULL,
                  lease_expires_at=NULL,next_attempt_at=clock_timestamp()+interval '1 minute',
                  last_error_code=?,updated_at=clock_timestamp()
                WHERE id=? AND lease_token=?
                """, error, claim.id(), claim.token()));
    }

    private void release(RequestClaim claim, String status, String error) {
        transactions.executeWithoutResult(ignored -> jdbc.update("""
                UPDATE fitness.account_erasure_requests SET status=?,lease_token=NULL,lease_expires_at=NULL,
                  last_error_code=?,updated_at=clock_timestamp()
                WHERE id=? AND lease_token=?
                """, status, error, claim.id(), claim.token()));
    }
}
