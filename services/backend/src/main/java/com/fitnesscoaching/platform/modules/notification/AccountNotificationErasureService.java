package com.fitnesscoaching.platform.modules.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountNotificationErasureService implements AccountNotificationErasure {
    private final JdbcTemplate jdbc;
    public AccountNotificationErasureService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokePushAndHideInbox(UUID userId) {
        jdbc.update("""
                UPDATE fitness.push_delivery_attempts a SET status='SKIPPED',claim_token=NULL,lease_expires_at=NULL
                FROM fitness.push_devices d WHERE a.device_id=d.id AND d.user_id=?
                  AND a.status IN ('QUEUED','SENDING','TICKET_ACCEPTED','CHECKING_RECEIPT')
                """, userId);
        jdbc.update("UPDATE fitness.push_devices SET active=false,expo_token='revoked:'||id::text WHERE user_id=?", userId);
        jdbc.update("UPDATE fitness.user_devices SET push_token=NULL WHERE user_id=?", userId);
        // The global bearer cutoff conceals the inbox immediately; contents are minimized by the worker.
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void minimizeAfterCleanup(UUID userId, UUID requestId) {
        // Preserve only reduced diagnostics for 30 days from each event, not raw token/ticket/provider payload.
        jdbc.update("""
                INSERT INTO fitness.account_erasure_push_diagnostics
                    (source_attempt_id,request_id,category,final_status,event_at,retain_until)
                SELECT a.id,?,a.category,a.status,
                  COALESCE(a.device_acknowledged_at,a.provider_accepted_at,a.attempted_at,a.next_attempt_at),
                  COALESCE(a.device_acknowledged_at,a.provider_accepted_at,a.attempted_at,a.next_attempt_at)
                    + interval '30 days'
                FROM fitness.push_delivery_attempts a
                JOIN fitness.push_devices d ON d.id=a.device_id WHERE d.user_id=?
                ON CONFLICT (source_attempt_id) DO NOTHING
                """, requestId, userId);
        jdbc.update("DELETE FROM fitness.notifications WHERE recipient_user_id=?", userId);
        jdbc.update("DELETE FROM fitness.notification_preferences WHERE user_id=?", userId);
        jdbc.update("DELETE FROM fitness.push_devices WHERE user_id=?", userId);
    }
}
