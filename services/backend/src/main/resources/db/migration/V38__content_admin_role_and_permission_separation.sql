-- Forward Migration V38: Separate Content management permissions from base ADMIN role into CONTENT_ADMIN
SET search_path TO fitness, public;

-- 1. Create or ensure CONTENT_ADMIN role exists according to governance specification
INSERT INTO roles (code, name, description)
VALUES ('CONTENT_ADMIN', 'Content Administrator', 'Exercise and knowledge governance authority')
ON CONFLICT (code) DO NOTHING;

-- 2. Remove Content permissions from base ADMIN role
DELETE FROM role_permissions
WHERE role_id = (SELECT id FROM roles WHERE code = 'ADMIN')
  AND permission_id IN (
      SELECT id FROM permissions
      WHERE code IN ('KNOWLEDGE_IMPORT', 'KNOWLEDGE_REVIEW', 'KNOWLEDGE_PUBLISH', 'KNOWLEDGE_ARCHIVE')
  );

-- 3. Grant Content permissions to CONTENT_ADMIN role
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'CONTENT_ADMIN'
  AND p.code IN ('KNOWLEDGE_IMPORT', 'KNOWLEDGE_REVIEW', 'KNOWLEDGE_PUBLISH', 'KNOWLEDGE_ARCHIVE')
ON CONFLICT DO NOTHING;
