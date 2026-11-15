-- Forward Migration V57: Seed Account Governance permissions and policies
SET search_path TO fitness, public;

-- 1. Ensure permissions exist
INSERT INTO permissions (code, description) VALUES
('USER_SUSPEND', 'Suspend or unsuspend user accounts'),
('USER_DISABLE', 'Disable user accounts permanently'),
('USER_ROLE_MANAGE', 'Manage user roles and administrative assignments'),
('SESSION_REVOKE', 'Revoke active user sessions and refresh tokens')
ON CONFLICT (code) DO NOTHING;

-- 2. Grant governance permissions to ADMIN role
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN'
  AND p.code IN ('USER_SUSPEND', 'USER_DISABLE', 'USER_ROLE_MANAGE', 'SESSION_REVOKE')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 3. Ensure RESTORE_ACCOUNT policy exists in admin_action_policies
INSERT INTO admin_action_policies (action_type, required_permission_id, requires_step_up, requires_privileged_access, max_step_up_age_minutes, is_active)
SELECT 'RESTORE_ACCOUNT', id, true, false, 15, true
FROM permissions
WHERE code = 'USER_SUSPEND'
ON CONFLICT (action_type) DO NOTHING;
