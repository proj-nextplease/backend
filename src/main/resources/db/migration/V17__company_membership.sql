-- Mô hình "Cấp quyền + Ủy quyền" cho Doanh nghiệp / CLB.
-- Thay quan hệ 1-1 (companies.owner_user_id) bằng membership nhiều người qua authority_nodes,
-- kèm bảng lời mời company_invitations (mời qua email + tự đăng nhập Supabase).

-- 1. Vai trò trong tổ chức trên authority_nodes (OWNER / MANAGER / MEMBER)
ALTER TABLE authority_nodes
    ADD COLUMN IF NOT EXISTS node_role VARCHAR(20) NOT NULL DEFAULT 'MEMBER';

ALTER TABLE authority_nodes DROP CONSTRAINT IF EXISTS ck_authority_nodes_node_role;
ALTER TABLE authority_nodes
    ADD CONSTRAINT ck_authority_nodes_node_role
        CHECK (node_role IN ('OWNER', 'MANAGER', 'MEMBER'));

-- Mỗi (company, user) chỉ có một quyền truy cập còn hiệu lực (ACTIVE/PENDING/SUSPENDED), bỏ qua bản đã REJECTED.
CREATE UNIQUE INDEX IF NOT EXISTS ux_authority_nodes_company_user_live
    ON authority_nodes(company_id, user_id)
    WHERE status <> 'REJECTED' AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_authority_nodes_node_role ON authority_nodes(node_role);

-- 2. Bảng lời mời thành viên tổ chức
CREATE TABLE IF NOT EXISTS company_invitations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    invited_email varchar(320) NOT NULL,
    node_role varchar(20) NOT NULL DEFAULT 'MEMBER',
    token_hash varchar(64) NOT NULL UNIQUE,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    invited_by uuid REFERENCES app_users(id),
    accepted_by uuid REFERENCES app_users(id),
    expires_at timestamptz NOT NULL,
    accepted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_company_invitations_role CHECK (node_role IN ('OWNER', 'MANAGER', 'MEMBER')),
    CONSTRAINT ck_company_invitations_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED'))
);

CREATE INDEX IF NOT EXISTS idx_company_invitations_company_id ON company_invitations(company_id);
CREATE INDEX IF NOT EXISTS idx_company_invitations_email ON company_invitations(lower(invited_email));
CREATE INDEX IF NOT EXISTS idx_company_invitations_status ON company_invitations(status);

CREATE TRIGGER trg_company_invitations_updated_at
    BEFORE UPDATE ON company_invitations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 3. Backfill: mỗi company hiện có được cấp một authority_node OWNER/ACTIVE cho owner_user_id,
--    để dữ liệu B2B cũ tiếp tục hoạt động sau khi refactor kiểm tra quyền.
INSERT INTO authority_nodes (company_id, user_id, node_type, node_role, status, approved_by, approved_at)
SELECT c.id,
       c.owner_user_id,
       CASE WHEN c.company_type = 'CLUB' THEN 'CLUB_LEADER' ELSE 'COMPANY_MANAGER' END,
       'OWNER',
       'ACTIVE',
       c.owner_user_id,
       now()
FROM companies c
WHERE NOT EXISTS (
    SELECT 1 FROM authority_nodes an
    WHERE an.company_id = c.id
      AND an.user_id = c.owner_user_id
      AND an.status <> 'REJECTED'
      AND an.deleted_at IS NULL
);
