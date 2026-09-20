SET search_path TO fitness, public;

-- Seed system roles if not already present.
-- ON CONFLICT DO NOTHING ensures idempotency and preserves any custom descriptions
-- if the database was previously seeded by dev scripts.
INSERT INTO roles (code, name, description) VALUES
('STUDENT', 'Student', 'Platform student member capability'),
('TRAINER', 'Trainer', 'Platform trainer coaching capability'),
('ADMIN', 'Platform Administrator', 'Platform administration and governance authority')
ON CONFLICT (code) DO NOTHING;
