package com.fitnesscoaching.platform.modules.auth.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens", schema = "fitness")
public class RefreshTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "device_name", length = 200)
    private String deviceName;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "rotated_from_id")
    private UUID rotatedFromId;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 200)
    private String revokeReason;

    protected RefreshTokenEntity() {
    }

    public RefreshTokenEntity(
            UUID userId,
            String tokenHash,
            String deviceName,
            Instant issuedAt,
            Instant expiresAt,
            UUID rotatedFromId
    ) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.deviceName = deviceName;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.rotatedFromId = rotatedFromId;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public String getDeviceName() { return deviceName; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public UUID getRotatedFromId() { return rotatedFromId; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevokeReason() { return revokeReason; }
}
