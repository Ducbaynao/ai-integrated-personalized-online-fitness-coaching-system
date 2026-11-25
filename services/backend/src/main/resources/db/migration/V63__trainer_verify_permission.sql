-- Forward Migration V63: Seed TRAINER_VERIFY permission and grant to ADMIN role
SET search_path TO fitness, public;

INSERT INTO permissions (code, description) VALUES
('TRAINER_VERIFY', 'Review and decide trainer verification applications')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN'
  AND p.code = 'TRAINER_VERIFY'
ON CONFLICT (role_id, permission_id) DO NOTHING;
