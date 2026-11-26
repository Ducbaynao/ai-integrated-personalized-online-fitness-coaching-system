package com.fitnesscoaching.platform.modules.coaching.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.coaching.application.port.out.CoachingQrStore;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingQrState.QrCodeRecord;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingQrState.QrRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcCoachingQrStore implements CoachingQrStore {
    private final JdbcTemplate jdbc;

    public JdbcCoachingQrStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<QrCodeRecord> QR_MAPPER = (rs, ignored) -> new QrCodeRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            QrRole.valueOf(rs.getString("capability_role")),
            rs.getString("public_code"),
            rs.getInt("payload_version"),
            rs.getString("status"),
            rs.getLong("version"),
            instant(rs.getTimestamp("issued_at")),
            instant(rs.getTimestamp("revoked_at")),
            rs.getString("revoke_reason")
    );

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }

    @Override
    public void lockOwner(UUID userId) {
        jdbc.query("SELECT id FROM fitness.users WHERE id = ? FOR NO KEY UPDATE",
                (RowCallbackHandler) rs -> { /* hold lock to end of transaction */ }, userId);
    }

    @Override
    public Optional<QrCodeRecord> findActiveByOwnerAndRole(UUID userId, QrRole role) {
        return first(jdbc.query("""
                SELECT id, user_id, capability_role, public_code, payload_version,
                       status, version, issued_at, revoked_at, revoke_reason
                FROM fitness.coaching_qr_codes
                WHERE user_id = ? AND capability_role = ? AND status = 'ACTIVE'
                LIMIT 1
                """, QR_MAPPER, userId, role.name()));
    }

    @Override
    public Optional<QrCodeRecord> findById(UUID qrId) {
        return first(jdbc.query("""
                SELECT id, user_id, capability_role, public_code, payload_version,
                       status, version, issued_at, revoked_at, revoke_reason
                FROM fitness.coaching_qr_codes
                WHERE id = ?
                """, QR_MAPPER, qrId));
    }

    @Override
    public Optional<QrCodeRecord> findActiveByPublicCode(String publicCode) {
        return first(jdbc.query("""
                SELECT id, user_id, capability_role, public_code, payload_version,
                       status, version, issued_at, revoked_at, revoke_reason
                FROM fitness.coaching_qr_codes
                WHERE public_code = ? AND status = 'ACTIVE'
                LIMIT 1
                """, QR_MAPPER, publicCode));
    }

    @Override
    public Optional<QrCodeRecord> insertIfAbsentActive(UUID userId, QrRole role, String publicCode, int payloadVersion, Instant issuedAt) {
        UUID id = UUID.randomUUID();
        List<QrCodeRecord> inserted = jdbc.query("""
                INSERT INTO fitness.coaching_qr_codes
                (id, user_id, capability_role, public_code, payload_version, status, version, issued_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 1, ?)
                ON CONFLICT (user_id, capability_role) WHERE status = 'ACTIVE' DO NOTHING
                RETURNING id, user_id, capability_role, public_code, payload_version,
                          status, version, issued_at, revoked_at, revoke_reason
                """, QR_MAPPER, id, userId, role.name(), publicCode, payloadVersion, ts(issuedAt));
        return first(inserted);
    }

    @Override
    public boolean revokeActive(UUID qrId, Instant revokedAt, String reason) {
        int updated = jdbc.update("""
                UPDATE fitness.coaching_qr_codes
                SET status = 'REVOKED', revoked_at = ?, revoke_reason = ?
                WHERE id = ? AND status = 'ACTIVE'
                """, ts(revokedAt), reason, qrId);
        return updated == 1;
    }

    @Override
    public QrCodeRecord insertRotatedActive(UUID userId, QrRole role, String publicCode, int payloadVersion, long newVersion, Instant issuedAt) {
        UUID id = UUID.randomUUID();
        return jdbc.queryForObject("""
                INSERT INTO fitness.coaching_qr_codes
                (id, user_id, capability_role, public_code, payload_version, status, version, issued_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                RETURNING id, user_id, capability_role, public_code, payload_version,
                          status, version, issued_at, revoked_at, revoke_reason
                """, QR_MAPPER, id, userId, role.name(), publicCode, payloadVersion, newVersion, ts(issuedAt));
    }
}
