SET search_path TO fitness, public;

CREATE TABLE coaching_qr_codes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    capability_role varchar(16) NOT NULL CHECK (capability_role IN ('STUDENT', 'TRAINER')),
    public_code varchar(32) NOT NULL,
    payload_version smallint NOT NULL DEFAULT 1 CHECK (payload_version = 1),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    issued_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    revoke_reason varchar(64),
    CONSTRAINT uq_coaching_qr_public_code UNIQUE (public_code),
    CONSTRAINT coaching_qr_revocation_ck CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL AND revoke_reason IS NULL)
        OR
        (status = 'REVOKED' AND revoked_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_active_coaching_qr_owner_role
    ON coaching_qr_codes(user_id, capability_role)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_coaching_qr_user_role
    ON coaching_qr_codes(user_id, capability_role, issued_at DESC);
