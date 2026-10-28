-- V13 — Mốc M9: sửa offline + đồng bộ (plan 2026-10-27-m9-chuc-nang.md, F2 / F3).
-- 1) sync_commands: chống gửi trùng lệnh theo (user, uuid) — hàng chèn CÙNG transaction với lệnh, chỉ lệnh thành công còn lại.
-- 2) sync_changes: MỘT hàng / bản ghi (dự án, section, task, label, filter), upsert bởi trigger ở mọi INSERT / UPDATE /
--    DELETE — gồm native UPDATE và xoá lan ON DELETE CASCADE mà service không thấy. xid = id transaction (pg_current_xact_id);
--    syncToken = xmin của snapshot lúc phát → lấy thay đổi theo xid ≥ token cũ không sót transaction commit muộn.
--    deleted = TRUE là tombstone (bản ghi đã xoá vĩnh viễn). Không FK tới bản ghi / workspace (tombstone sống sau bản ghi).
-- Mốc khác cắm bảng mới: CREATE TRIGGER … EXECUTE FUNCTION sync_track('<TYPE>', '<workspace|user|project|task>', '<cột>')
-- (hàm nhận tham số, KHÔNG cần CREATE OR REPLACE) — xem plan mục 8.3.

CREATE TABLE sync_commands (
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    command_uuid UUID        NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    result       TEXT        NOT NULL,                 -- JSON: {"tempIdMapping": {...}} của lần chạy thành công
    created_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, command_uuid)
);
CREATE INDEX ix_sync_commands_created_at ON sync_commands (created_at);

CREATE TABLE sync_changes (
    entity_type  VARCHAR(32) NOT NULL,                 -- PROJECT, SECTION, TASK, LABEL, FILTER (mốc sau thêm)
    entity_id    UUID        NOT NULL,
    workspace_id UUID,                                 -- phạm vi theo workspace (dự án, section, task)
    user_id      UUID,                                 -- phạm vi theo tài khoản (label, filter)
    deleted      BOOLEAN     NOT NULL DEFAULT FALSE,
    xid          BIGINT      NOT NULL,
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (entity_type, entity_id),
    CONSTRAINT ck_sync_changes_scope CHECK (workspace_id IS NOT NULL OR user_id IS NOT NULL)
);
CREATE INDEX ix_sync_changes_workspace_xid ON sync_changes (workspace_id, xid) WHERE workspace_id IS NOT NULL;
CREATE INDEX ix_sync_changes_user_xid ON sync_changes (user_id, xid) WHERE user_id IS NOT NULL;
CREATE INDEX ix_sync_changes_tombstone_age ON sync_changes (changed_at) WHERE deleted;

-- Trigger AFTER ROW. TG_ARGV: [0] entity_type, [1] loại phạm vi, [2] cột chứa phạm vi:
--   workspace → cột là workspace_id; user → cột là user_id; project → cột là project_id (tra workspace của dự án);
--   task → cột là task_id (tra workspace của task).
-- DELETE: phạm vi tra không ra (cha đã bị xoá trước trong cùng lệnh xoá lan) → lấy phạm vi đã lưu. Chủ phạm vi đã mất
-- (xoá workspace / tài khoản) → xoá hẳn hàng thay vì để tombstone (không giữ id của tài khoản đã xoá).
CREATE FUNCTION sync_track() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    v_type  VARCHAR(32) := TG_ARGV[0];
    v_scope TEXT := TG_ARGV[1];
    v_row   JSONB;
    v_id    UUID;
    v_ref   UUID;
    v_ws    UUID;
    v_user  UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_row := to_jsonb(OLD);
    ELSE
        v_row := to_jsonb(NEW);
    END IF;
    v_id := (v_row ->> 'id')::uuid;
    v_ref := (v_row ->> TG_ARGV[2])::uuid;
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

    INSERT INTO sync_changes AS c (entity_type, entity_id, workspace_id, user_id, deleted, xid, changed_at)
    VALUES (v_type, v_id, v_ws, v_user, TG_OP = 'DELETE', pg_current_xact_id()::text::bigint, now())
    ON CONFLICT (entity_type, entity_id) DO UPDATE
        SET workspace_id = COALESCE(EXCLUDED.workspace_id, c.workspace_id),
            user_id      = COALESCE(EXCLUDED.user_id, c.user_id),
            deleted      = EXCLUDED.deleted,
            xid          = EXCLUDED.xid,
            changed_at   = EXCLUDED.changed_at;
    RETURN NULL;
END
$$;

-- Bảng phụ chỉ làm đổi bản ghi cha (task_labels → task; favorites → dự án / label / filter). TG_ARGV: [0] entity_type cố
-- định, hoặc '*' khi lấy từ cột [2]; [1] cột id của bản ghi cha. Chỉ cập nhật hàng còn sống: xoá lan task_labels khi task
-- bị xoá KHÔNG được biến tombstone của task thành "đổi". (Tham số trigger luôn là chuỗi → không dùng NULL.)
CREATE FUNCTION sync_bump() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    v_row  JSONB;
    v_type VARCHAR(32);
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_row := to_jsonb(OLD);
    ELSE
        v_row := to_jsonb(NEW);
    END IF;
    IF TG_ARGV[0] = '*' THEN
        v_type := v_row ->> TG_ARGV[2];
    ELSE
        v_type := TG_ARGV[0];
    END IF;
    UPDATE sync_changes c
    SET xid = pg_current_xact_id()::text::bigint, changed_at = now()
    WHERE c.entity_type = v_type AND c.entity_id = (v_row ->> TG_ARGV[1])::uuid AND NOT c.deleted;
    RETURN NULL;
END
$$;

-- Dự án đổi workspace (M8a: chuyển vào team) → section của nó đổi phạm vi theo (section lưu phạm vi lúc ghi).
CREATE FUNCTION sync_project_workspace_moved() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE sync_changes c
    SET workspace_id = NEW.workspace_id, xid = pg_current_xact_id()::text::bigint, changed_at = now()
    WHERE c.entity_type = 'SECTION' AND c.entity_id IN (SELECT s.id FROM sections s WHERE s.project_id = NEW.id);
    RETURN NULL;
END
$$;

CREATE TRIGGER trg_sync_projects AFTER INSERT OR UPDATE OR DELETE ON projects
    FOR EACH ROW EXECUTE FUNCTION sync_track('PROJECT', 'workspace', 'workspace_id');
CREATE TRIGGER trg_sync_projects_workspace AFTER UPDATE OF workspace_id ON projects
    FOR EACH ROW WHEN (OLD.workspace_id IS DISTINCT FROM NEW.workspace_id)
    EXECUTE FUNCTION sync_project_workspace_moved();
CREATE TRIGGER trg_sync_sections AFTER INSERT OR UPDATE OR DELETE ON sections
    FOR EACH ROW EXECUTE FUNCTION sync_track('SECTION', 'project', 'project_id');
CREATE TRIGGER trg_sync_tasks AFTER INSERT OR UPDATE OR DELETE ON tasks
    FOR EACH ROW EXECUTE FUNCTION sync_track('TASK', 'workspace', 'workspace_id');
CREATE TRIGGER trg_sync_labels AFTER INSERT OR UPDATE OR DELETE ON labels
    FOR EACH ROW EXECUTE FUNCTION sync_track('LABEL', 'user', 'user_id');
CREATE TRIGGER trg_sync_filters AFTER INSERT OR UPDATE OR DELETE ON filters
    FOR EACH ROW EXECUTE FUNCTION sync_track('FILTER', 'user', 'user_id');
CREATE TRIGGER trg_sync_task_labels AFTER INSERT OR DELETE ON task_labels
    FOR EACH ROW EXECUTE FUNCTION sync_bump('TASK', 'task_id');
CREATE TRIGGER trg_sync_favorites AFTER INSERT OR DELETE ON favorites
    FOR EACH ROW EXECUTE FUNCTION sync_bump('*', 'target_id', 'target_type');

-- Backfill bản ghi đã có (xid 0: mọi token đều mới hơn, chỉ full sync thấy chúng).
INSERT INTO sync_changes (entity_type, entity_id, workspace_id, user_id, deleted, xid)
SELECT 'PROJECT', p.id, p.workspace_id, NULL, FALSE, 0 FROM projects p;
INSERT INTO sync_changes (entity_type, entity_id, workspace_id, user_id, deleted, xid)
SELECT 'SECTION', s.id, p.workspace_id, NULL, FALSE, 0 FROM sections s JOIN projects p ON p.id = s.project_id;
INSERT INTO sync_changes (entity_type, entity_id, workspace_id, user_id, deleted, xid)
SELECT 'TASK', t.id, t.workspace_id, NULL, FALSE, 0 FROM tasks t;
INSERT INTO sync_changes (entity_type, entity_id, workspace_id, user_id, deleted, xid)
SELECT 'LABEL', l.id, NULL, l.user_id, FALSE, 0 FROM labels l;
INSERT INTO sync_changes (entity_type, entity_id, workspace_id, user_id, deleted, xid)
SELECT 'FILTER', f.id, NULL, f.user_id, FALSE, 0 FROM filters f;
