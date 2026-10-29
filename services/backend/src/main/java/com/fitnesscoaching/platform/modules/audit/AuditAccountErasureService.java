package com.fitnesscoaching.platform.modules.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuditAccountErasureService implements AuditAccountErasure {
    private final JdbcTemplate jdbc;
    public AuditAccountErasureService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void redactForAccount(UUID userId, UUID requestId) {
        // Record the fingerprint of the exact prior row before applying the restricted trigger exception.
        jdbc.update("""
                INSERT INTO fitness.account_erasure_audit_redactions(request_id,source_table,source_id,prior_sha256)
                SELECT ?,'audit_logs',a.id::text,encode(fitness.digest(to_jsonb(a)::text,'sha256'),'hex')
                FROM fitness.audit_logs a JOIN fitness.users u ON u.id=?
                WHERE a.actor_user_id=? OR a.target_id=?
                   OR to_jsonb(a)::text LIKE '%'||?::text||'%'
                   OR to_jsonb(a)::text ILIKE '%'||u.email::text||'%'
                ON CONFLICT (request_id,source_table,source_id) DO NOTHING
                """, requestId, userId, userId, userId, userId);
        jdbc.update("""
                UPDATE fitness.audit_logs a SET actor_user_id=NULL,target_id=NULL,request_id=NULL,
                    ip_address=NULL,user_agent=NULL,before_data=NULL,after_data=NULL,metadata='{}'::jsonb
                FROM fitness.account_erasure_audit_redactions e
                WHERE e.request_id=? AND e.source_table='audit_logs' AND e.source_id=a.id::text
                  AND e.prior_sha256=encode(fitness.digest(to_jsonb(a)::text,'sha256'),'hex')
                """, requestId);

        jdbc.update("""
                INSERT INTO fitness.account_erasure_audit_redactions(request_id,source_table,source_id,prior_sha256)
                SELECT ?,'security_events',s.id::text,encode(fitness.digest(to_jsonb(s)::text,'sha256'),'hex')
                FROM fitness.security_events s JOIN fitness.users u ON u.id=?
                WHERE s.user_id=? OR to_jsonb(s)::text LIKE '%'||?::text||'%'
                   OR to_jsonb(s)::text ILIKE '%'||u.email::text||'%'
                ON CONFLICT (request_id,source_table,source_id) DO NOTHING
                """, requestId, userId, userId, userId);
        jdbc.update("""
                UPDATE fitness.security_events s SET user_id=NULL,device_id=NULL,
                    ip_address=NULL,user_agent=NULL,details='{}'::jsonb
                FROM fitness.account_erasure_audit_redactions e
                WHERE e.request_id=? AND e.source_table='security_events' AND e.source_id=s.id::text
                  AND e.prior_sha256=encode(fitness.digest(to_jsonb(s)::text,'sha256'),'hex')
                """, requestId);

        jdbc.update("""
                INSERT INTO fitness.account_erasure_audit_redactions(request_id,source_table,source_id,prior_sha256)
                SELECT ?,'user_account_status_history',h.id::text,
                       encode(fitness.digest(to_jsonb(h)::text,'sha256'),'hex')
                FROM fitness.user_account_status_history h WHERE h.user_id=?
                ON CONFLICT (request_id,source_table,source_id) DO NOTHING
                """, requestId, userId);
        jdbc.update("""
                UPDATE fitness.user_account_status_history h SET changed_by=NULL,
                    reason='ACCOUNT_ERASURE_REDACTED'
                FROM fitness.account_erasure_audit_redactions e
                WHERE e.request_id=? AND e.source_table='user_account_status_history'
                  AND e.source_id=h.id::text
                  AND e.prior_sha256=encode(fitness.digest(to_jsonb(h)::text,'sha256'),'hex')
                """, requestId);
    }
}
