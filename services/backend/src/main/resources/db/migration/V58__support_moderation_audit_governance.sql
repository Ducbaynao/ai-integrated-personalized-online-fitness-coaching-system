-- Forward Migration V58: Support, Moderation, Audit and Privileged Access permissions and policies
SET search_path TO fitness, public;

-- 1. Ensure permissions exist
INSERT INTO permissions (code, description) VALUES
('SUPPORT_MANAGE', 'Manage and resolve customer support cases'),
('MODERATION_MANAGE', 'Review and action user reports and moderation cases'),
('AUDIT_VIEW', 'Search and inspect immutable audit trail logs'),
('PRIVILEGED_ACCESS_MANAGE', 'Request, approve and manage time-bound privileged data access'),
('DATA_CORRECTION_EXECUTE', 'Execute governed domain data corrections')
ON CONFLICT (code) DO NOTHING;

-- 2. Grant governance permissions to ADMIN role
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN'
  AND p.code IN (
    'SUPPORT_MANAGE',
    'MODERATION_MANAGE',
    'AUDIT_VIEW',
    'PRIVILEGED_ACCESS_MANAGE',
    'DATA_CORRECTION_EXECUTE'
  )
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 3. Ensure admin_action_policies has entries for governance actions
INSERT INTO admin_action_policies (action_type, required_permission_id, requires_step_up, requires_privileged_access, max_step_up_age_minutes, is_active)
SELECT 'MODERATION_ACTION', id, false, false, 15, true
FROM permissions
WHERE code = 'MODERATION_MANAGE'
ON CONFLICT (action_type) DO NOTHING;

INSERT INTO admin_action_policies (action_type, required_permission_id, requires_step_up, requires_privileged_access, max_step_up_age_minutes, is_active)
SELECT 'PRIVILEGED_ACCESS_REQUEST', id, true, false, 15, true
FROM permissions
WHERE code = 'PRIVILEGED_ACCESS_MANAGE'
ON CONFLICT (action_type) DO NOTHING;

-- 4. Update moderation_actions action_type check to include DISABLE
ALTER TABLE moderation_actions DROP CONSTRAINT IF EXISTS moderation_actions_action_type_check;
ALTER TABLE moderation_actions ADD CONSTRAINT moderation_actions_action_type_check
    CHECK (action_type IN ('WARNING','CONTENT_RESTRICTION','ACCOUNT_RESTRICTION','SUSPENSION','DISABLE','ESCALATION','NO_ACTION'));
