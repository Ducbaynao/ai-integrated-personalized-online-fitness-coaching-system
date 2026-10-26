package com.fitnesscoaching.platform.modules.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Claims and finalizes in short database transactions; provider I/O never holds a database lock. */
@Component
public class PushDeliveryWorker {
    private static final int BATCH_SIZE = 20;
    private static final int MAX_RECEIPT_CHECKS = 20;
    private final JdbcTemplate jdbc;
    private final InAppNotificationService notices;
    private final ExpoPushGateway expo;
    private final TransactionTemplate transactions;
    private final boolean enabled;

    public PushDeliveryWorker(JdbcTemplate jdbc, InAppNotificationService notices, ExpoPushGateway expo,
                              PlatformTransactionManager transactionManager,
                              @Value("${push.expo.enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.notices = notices; this.expo = expo;
        this.transactions = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
    }

    private record Claim(UUID id, UUID notificationId, UUID recipient, String token, UUID claimToken) {}

    @Scheduled(fixedDelayString = "${push.expo.worker-delay-ms:60000}")
    public void dispatch() {
        if (!enabled) return;
        transactions.executeWithoutResult(ignored -> recoverExpiredSends());
        for (int n = 0; n < BATCH_SIZE; n++) {
            List<Claim> claims = transactions.execute(ignored -> claimSends());
            if (claims.isEmpty()) break;
            Claim claim = claims.getFirst();
            if (!Boolean.TRUE.equals(transactions.execute(ignored -> eligibleToSend(claim)))) continue;
            // A timeout or crash after this call may have submitted the push. Never blindly resend it.
            ExpoPushGateway.Result result = expo.send(claim.token(), claim.notificationId());
            transactions.executeWithoutResult(ignored -> finalizeSend(claim, result));
        }
    }

    private void recoverExpiredSends() {
        jdbc.update("""
                UPDATE fitness.push_delivery_attempts
                SET status='FAILED',provider_error='TICKET_OUTCOME_UNKNOWN',
                    claim_token=NULL,lease_expires_at=NULL
                WHERE status='SENDING' AND lease_expires_at < clock_timestamp()
                """);
    }

    private List<Claim> claimSends() {
        List<Claim> candidates = jdbc.query("""
                SELECT a.id,a.notification_id,n.recipient_user_id,d.expo_token
                FROM fitness.push_delivery_attempts a
                JOIN fitness.notifications n ON n.id=a.notification_id
                JOIN fitness.push_devices d ON d.id=a.device_id
                WHERE a.status='QUEUED' AND a.next_attempt_at <= clock_timestamp()
                  AND a.send_attempt_count=0
                ORDER BY a.next_attempt_at,a.id
                FOR UPDATE OF a SKIP LOCKED LIMIT 1
                """, (rs, row) -> new Claim(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), UUID.randomUUID()));
        for (Claim claim : candidates) jdbc.update("""
                UPDATE fitness.push_delivery_attempts SET status='SENDING',claim_token=?,
                    lease_expires_at=clock_timestamp()+interval '1 minute',
                    send_attempt_count=send_attempt_count+1
                WHERE id=? AND status='QUEUED' AND send_attempt_count=0
                """, claim.claimToken(), claim.id());
        return candidates;
    }

    private boolean eligibleToSend(Claim claim) {
        List<Eligibility> current = jdbc.query("""
                SELECT d.active,p.enabled,p.timezone,p.quiet_hours_start,p.quiet_hours_end,d.expo_token
                FROM fitness.push_delivery_attempts a
                JOIN fitness.push_devices d ON d.id=a.device_id
                LEFT JOIN fitness.notification_preferences p ON p.user_id=d.user_id
                  AND p.channel='PUSH' AND p.event_type=a.category
                WHERE a.id=? AND a.status='SENDING' AND a.claim_token=?
                  AND a.lease_expires_at > clock_timestamp()
                """, (rs, row) -> new Eligibility(rs.getBoolean(1), rs.getBoolean(2), rs.getString(3),
                rs.getTime(4) == null ? null : rs.getTime(4).toLocalTime(),
                rs.getTime(5) == null ? null : rs.getTime(5).toLocalTime(),rs.getString(6)),
                claim.id(), claim.claimToken());
        if (current.isEmpty()) return false;
        Eligibility value = current.getFirst();
        if (!value.active() || !value.optedIn() || value.timezone() == null
                || !claim.token().equals(value.currentToken())
                || !notices.canReadNotice(claim.recipient(), claim.notificationId())) {
            clearClaim(claim, "SKIPPED", null, null);
            return false;
        }
        Instant now = databaseNow();
        if (PushQuietHours.isQuiet(now, value.timezone(), value.quietStart(), value.quietEnd())) {
            clearClaim(claim, "QUEUED", null, PushQuietHours.nextAllowed(now, value.timezone(),
                    value.quietStart(), value.quietEnd()));
            return false;
        }
        jdbc.update("""
                UPDATE fitness.push_delivery_attempts SET attempted_at=clock_timestamp()
                WHERE id=? AND status='SENDING' AND claim_token=?
                """, claim.id(), claim.claimToken());
        return true;
    }

    private void clearClaim(Claim claim, String status, String error, Instant nextAttempt) {
        jdbc.update("""
                UPDATE fitness.push_delivery_attempts SET status=?,provider_error=?,
                    next_attempt_at=COALESCE(?,next_attempt_at),claim_token=NULL,lease_expires_at=NULL,
                    send_attempt_count=CASE WHEN ?='QUEUED' THEN 0 ELSE send_attempt_count END
                WHERE id=? AND status='SENDING' AND claim_token=?
                """, status, error, nextAttempt == null ? null : Timestamp.from(nextAttempt),
                status, claim.id(), claim.claimToken());
    }

    private void finalizeSend(Claim claim, ExpoPushGateway.Result result) {
        int changed = jdbc.update("""
                UPDATE fitness.push_delivery_attempts SET status=?,expo_ticket_id=?,provider_error=?,
                  next_attempt_at=clock_timestamp()+interval '30 seconds',
                  claim_token=NULL,lease_expires_at=NULL
                WHERE id=? AND status='SENDING' AND claim_token=?
                  AND lease_expires_at > clock_timestamp()
                """, result.accepted() ? "TICKET_ACCEPTED" : "FAILED", result.id(), result.error(),
                claim.id(), claim.claimToken());
        if (changed > 0 && result.accepted()) jdbc.update("""
                UPDATE fitness.notification_deliveries SET status='SENT',
                  provider_message_id=?,attempt_count=attempt_count+1,last_attempt_at=clock_timestamp()
                WHERE notification_id=? AND channel='PUSH'
                """, result.id(), claim.notificationId());
    }

    @Scheduled(fixedDelayString = "${push.expo.receipt-delay-ms:120000}")
    public void inspectReceipts() {
        if (!enabled) return;
        transactions.executeWithoutResult(ignored -> recoverExpiredReceipts());
        for (int n = 0; n < BATCH_SIZE; n++) {
            List<Claim> claims = transactions.execute(ignored -> claimReceipts());
            if (claims.isEmpty()) break;
            Claim claim = claims.getFirst();
            ExpoPushGateway.Result result = expo.receipt(claim.token());
            transactions.executeWithoutResult(ignored -> finalizeReceipt(claim, result));
        }
    }

    private void recoverExpiredReceipts() {
        jdbc.update("""
                UPDATE fitness.push_delivery_attempts SET status='FAILED',
                    provider_error='RECEIPT_UNAVAILABLE_EXHAUSTED'
                WHERE status='TICKET_ACCEPTED' AND receipt_attempt_count >= ?
                """, MAX_RECEIPT_CHECKS);
        jdbc.update("""
                UPDATE fitness.push_delivery_attempts SET status='TICKET_ACCEPTED',
                    next_attempt_at=clock_timestamp(),claim_token=NULL,lease_expires_at=NULL
                WHERE status='CHECKING_RECEIPT' AND lease_expires_at < clock_timestamp()
                  AND receipt_attempt_count < ?
                """, MAX_RECEIPT_CHECKS);
        jdbc.update("""
                UPDATE fitness.push_delivery_attempts SET status='FAILED',
                    provider_error='RECEIPT_UNAVAILABLE_EXHAUSTED',claim_token=NULL,lease_expires_at=NULL
                WHERE status='CHECKING_RECEIPT' AND lease_expires_at < clock_timestamp()
                  AND receipt_attempt_count >= ?
                """, MAX_RECEIPT_CHECKS);
    }

    private List<Claim> claimReceipts() {
        List<Claim> candidates = jdbc.query("""
                SELECT a.id,a.notification_id,n.recipient_user_id,a.expo_ticket_id
                FROM fitness.push_delivery_attempts a
                JOIN fitness.notifications n ON n.id=a.notification_id
                WHERE a.status='TICKET_ACCEPTED' AND a.expo_ticket_id IS NOT NULL
                  AND a.next_attempt_at <= clock_timestamp() AND a.receipt_attempt_count < ?
                ORDER BY a.next_attempt_at,a.id FOR UPDATE OF a SKIP LOCKED LIMIT 1
                """, (rs, row) -> new Claim(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), UUID.randomUUID()),
                MAX_RECEIPT_CHECKS);
        for (Claim claim : candidates) jdbc.update("""
                UPDATE fitness.push_delivery_attempts SET status='CHECKING_RECEIPT',claim_token=?,
                    lease_expires_at=clock_timestamp()+interval '1 minute',
                    receipt_attempt_count=receipt_attempt_count+1
                WHERE id=? AND status='TICKET_ACCEPTED'
                """, claim.claimToken(), claim.id());
        return candidates;
    }

    private void finalizeReceipt(Claim claim, ExpoPushGateway.Result result) {
        String status = result.accepted() ? "PROVIDER_ACCEPTED" : "FAILED";
        Instant nextAttempt = null;
        if ("RECEIPT_UNAVAILABLE".equals(result.error())) {
            List<Integer> attemptsFound = jdbc.query("""
                    SELECT receipt_attempt_count FROM fitness.push_delivery_attempts
                    WHERE id=? AND status='CHECKING_RECEIPT' AND claim_token=?
                    """, (rs, row) -> rs.getInt(1), claim.id(), claim.claimToken());
            if (attemptsFound.isEmpty()) return;
            int attempts = attemptsFound.getFirst();
            status = attempts >= MAX_RECEIPT_CHECKS ? "FAILED" : "TICKET_ACCEPTED";
            if (attempts < MAX_RECEIPT_CHECKS) nextAttempt = databaseNow().plusSeconds(30);
        }
        jdbc.update("""
                UPDATE fitness.push_delivery_attempts SET status=?,provider_error=?,
                  next_attempt_at=COALESCE(?,next_attempt_at),
                  provider_accepted_at=CASE WHEN ? THEN clock_timestamp() ELSE provider_accepted_at END,
                  claim_token=NULL,lease_expires_at=NULL
                WHERE id=? AND status='CHECKING_RECEIPT' AND claim_token=?
                  AND lease_expires_at > clock_timestamp()
                """, status, result.error(), nextAttempt == null ? null : Timestamp.from(nextAttempt),
                result.accepted(), claim.id(), claim.claimToken());
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
    }

    static boolean quietNow(Instant at, String timezone, LocalTime start, LocalTime end) {
        return PushQuietHours.isQuiet(at, timezone, start, end);
    }

    private record Eligibility(boolean active, boolean optedIn, String timezone,
                               LocalTime quietStart, LocalTime quietEnd, String currentToken) {}
}
