-- V20 — Tích hợp M8a + M9: sync_changes lưu thêm DỰ ÁN của bản ghi (project_id) để lọc đổi / tombstone theo quyền DỰ ÁN.
-- Trước đây chỉ có workspace_id: người cộng tác của MỘT dự án trong workspace cá nhân của chủ (hoặc thành viên team
-- chỉ được xem một dự án Restricted) sẽ nhận id đổi / xoá của mọi dự án khác trong workspace đó.
-- PROJECT: project_id = id của chính dự án; SECTION / TASK: project_id của hàng. Các loại khác (LABEL, FILTER) để NULL.

ALTER TABLE sync_changes ADD COLUMN project_id UUID;

UPDATE sync_changes c SET project_id = c.entity_id WHERE c.entity_type = 'PROJECT';
UPDATE sync_changes c SET project_id = s.project_id FROM sections s
    WHERE c.entity_type = 'SECTION' AND s.id = c.entity_id;
UPDATE sync_changes c SET project_id = t.project_id FROM tasks t
    WHERE c.entity_type = 'TASK' AND t.id = c.entity_id;

CREATE OR REPLACE FUNCTION sync_track() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    v_type  VARCHAR(32) := TG_ARGV[0];
    v_scope TEXT := TG_ARGV[1];
    v_row   JSONB;
    v_id    UUID;
    v_ref   UUID;
    v_ws    UUID;
    v_user  UUID;
    v_proj  UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_row := to_jsonb(OLD);
    ELSE
        v_row := to_jsonb(NEW);
    END IF;
    v_id := (v_row ->> 'id')::uuid;
    v_ref := (v_row ->> TG_ARGV[2])::uuid;
    IF v_type = 'PROJECT' THEN
        v_proj := v_id;
    ELSIF v_type IN ('SECTION', 'TASK') THEN
        v_proj := (v_row ->> 'project_id')::uuid;
    END IF;
    IF v_scope = 'workspace' THEN
        v_ws := v_ref;
    ELSIF v_scope = 'user' THEN
        v_user := v_ref;
    ELSIF v_scope = 'project' THEN
        SELECT p.workspace_id INTO v_ws FROM projects p WHERE p.id = v_ref;
    ELSIF v_scope = 'task' THEN
        SELECT t.workspace_id INTO v_ws FROM tasks t WHERE t.id = v_ref;
    ELSE
        RAISE EXCEPTION 'sync_track: phạm vi không hỗ trợ %', v_scope;
    END IF;

    IF TG_OP = 'DELETE' THEN
        IF v_ws IS NULL AND v_user IS NULL THEN
            SELECT c.workspace_id, c.user_id INTO v_ws, v_user
            FROM sync_changes c WHERE c.entity_type = v_type AND c.entity_id = v_id;
        END IF;
        IF (v_ws IS NULL AND v_user IS NULL)
                OR (v_ws IS NOT NULL AND NOT EXISTS (SELECT 1 FROM workspaces w WHERE w.id = v_ws))
                OR (v_user IS NOT NULL AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = v_user)) THEN
            DELETE FROM sync_changes c WHERE c.entity_type = v_type AND c.entity_id = v_id;
            RETURN NULL;
        END IF;
    ELSIF v_ws IS NULL AND v_user IS NULL THEN
        RETURN NULL; -- không xác định được phạm vi (không xảy ra với ràng buộc FK hiện có)
    END IF;

    INSERT INTO sync_changes AS c (entity_type, entity_id, workspace_id, user_id, project_id, deleted, xid, changed_at)
    VALUES (v_type, v_id, v_ws, v_user, v_proj, TG_OP = 'DELETE', pg_current_xact_id()::text::bigint, now())
    ON CONFLICT (entity_type, entity_id) DO UPDATE
        SET workspace_id = COALESCE(EXCLUDED.workspace_id, c.workspace_id),
            user_id      = COALESCE(EXCLUDED.user_id, c.user_id),
            project_id   = COALESCE(EXCLUDED.project_id, c.project_id),
            deleted      = EXCLUDED.deleted,
            xid          = EXCLUDED.xid,
            changed_at   = EXCLUDED.changed_at;
    RETURN NULL;
END
$$;
