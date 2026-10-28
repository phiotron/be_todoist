package com.taskly.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Kiểm trực tiếp bằng SQL những gì V12__m8b_collab.sql tạo (plan M8b). */
class M8bMigrationTest extends IntegrationTestBase {

    private UUID user() throws Exception {
        return registerUser(uniqueEmail()).id();
    }

    private void insertComment(UUID id, UUID taskId, UUID projectId, UUID authorId, String content) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbcTemplate.update("insert into comments (id, task_id, project_id, author_id, content, created_at, updated_at) "
                + "values (?, ?, ?, ?, ?, ?, ?)", id, taskId, projectId, authorId, content, now, now);
    }

    @Test
    void flywayAppliedVersion12() {
        assertThat(jdbcTemplate.queryForObject("select success from flyway_schema_history where version = '12'",
                Boolean.class)).isTrue();
    }

    @Test
    void commentTargetIsExactlyOneOfTaskOrProject() throws Exception {
        UUID user = user();
        UUID project = testData.project(user, "P");
        UUID task = testData.task(user, project, "T");
        insertComment(UUID.randomUUID(), task, null, user, "ok");
        insertComment(UUID.randomUUID(), null, project, user, "ok");
        assertThatThrownBy(() -> insertComment(UUID.randomUUID(), task, project, user, "both"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertComment(UUID.randomUUID(), null, null, user, "none"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void commentContentLimitedTo15000Chars() throws Exception {
        UUID user = user();
        UUID task = testData.task(user, testData.inboxOf(user), "T");
        insertComment(UUID.randomUUID(), task, null, user, "a".repeat(15_000));
        assertThatThrownBy(() -> insertComment(UUID.randomUUID(), task, null, user, "a".repeat(15_001)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void authorDeletionKeepsComment_withNullAuthor() throws Exception {
        UUID author = user();
        UUID owner = user();
        UUID task = testData.task(owner, testData.inboxOf(owner), "T");
        UUID id = UUID.randomUUID();
        insertComment(id, task, null, author, "còn mãi");
        jdbcTemplate.update("delete from workspaces where owner_id = ?", author); // owner_id không cascade
        jdbcTemplate.update("delete from users where id = ?", author);
        assertThat(jdbcTemplate.queryForObject("select author_id from comments where id = ?", UUID.class, id)).isNull();
    }

    @Test
    void onlyOneAttachmentPerComment() throws Exception {
        UUID user = user();
        UUID task = testData.task(user, testData.inboxOf(user), "T");
        UUID comment = UUID.randomUUID();
        insertComment(comment, task, null, user, "x");
        Timestamp now = Timestamp.from(clock.instant());
        String sql = "insert into comment_attachments (id, uploader_id, comment_id, file_name, content_type, size_bytes, "
                + "storage_key, created_at) values (?, ?, ?, 'a', 'text/plain', 1, ?, ?)";
        jdbcTemplate.update(sql, UUID.randomUUID(), user, comment, UUID.randomUUID().toString().replace("-", ""), now);
        assertThatThrownBy(() -> jdbcTemplate.update(sql, UUID.randomUUID(), user, comment,
                UUID.randomUUID().toString().replace("-", ""), now)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void avatarColumnsAreAllOrNothing_andOnlyJpegPng() throws Exception {
        UUID user = user();
        Timestamp now = Timestamp.from(clock.instant());
        assertThatThrownBy(() -> jdbcTemplate.update("update users set avatar_key = 'k' where id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("update users set avatar_key = 'k', avatar_content_type = "
                + "'image/gif', avatar_updated_at = ? where id = ?", now, user))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update("update users set avatar_key = 'k', avatar_content_type = 'image/png', "
                + "avatar_updated_at = ? where id = ?", now, user);
    }

    @Test
    void notificationTypeAndSettingScopeAreChecked() throws Exception {
        UUID user = user();
        Timestamp now = Timestamp.from(clock.instant());
        assertThatThrownBy(() -> jdbcTemplate.update("insert into notifications (id, user_id, type, created_at) "
                + "values (?, ?, 'KHAC', ?)", UUID.randomUUID(), user, now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("insert into notification_settings (user_id, scope, event, enabled) "
                + "values (?, 'GLOBAL', 'X', true)", user)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
