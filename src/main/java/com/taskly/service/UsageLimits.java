package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;
import com.taskly.common.error.ProblemTexts;
import com.taskly.entity.LimitKind;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nơi DUY NHẤT kiểm giới hạn theo gói Pro (spec 2.1, 8.2.9). check(...) thuần: chỉ đọc cấu hình taskly.limits.*; service
 * sở hữu bảng tự đếm rồi gọi check (quyết định Q28). Vượt → 422 {code: "limit-reached", limit: LimitKind, max}.
 * CHỐNG ĐUA (spec 8.2 #9, C5): đếm-rồi-chèn không an toàn khi hai request song song → service gọi lock* TRƯỚC khi đếm:
 * SELECT … FOR NO KEY UPDATE hàng cha (workspace cho đếm dự án; project cho đếm section / task đang mở; user cho đếm theo tài
 * khoản — M2 labels / filters, M4 reminders) trong CÙNG transaction của service (Propagation.MANDATORY — gọi ngoài
 * transaction là lỗi lập trình). Request thứ hai chờ tới khi request đầu commit rồi mới đếm. Nhiều đích → khoá theo id
 * TĂNG DẦN (lockProjects) để hai request khoá ngược thứ tự không deadlock.
 * Dùng FOR NO KEY UPDATE (không phải FOR UPDATE): khoá ngoại của hàng con (task → project / workspace, project con → cha)
 * lấy FOR KEY SHARE trên hàng cha; FOR UPDATE xung đột với nó nên Tx A (khoá project rồi chèn task) và Tx B (khoá
 * workspace rồi chèn project con) có thể deadlock chéo. NO KEY UPDATE vẫn loại trừ lẫn nhau giữa các bên đếm nhưng cho
 * phép chèn hàng con. Lỗi khoá còn sót → GlobalExceptionHandler trả 409.
 */
@Component
public class UsageLimits {

    private final Map<LimitKind, Integer> configured = new EnumMap<>(LimitKind.class);
    /** Chỉ test dùng (overrideMax) để hạ giới hạn mà không tạo Spring context mới; IntegrationTestBase gỡ mỗi test. */
    private final Map<LimitKind, Integer> overrides = new ConcurrentHashMap<>();

    /** Chỉ cần cho lock*; unit test của check(...) tạo UsageLimits bằng constructor và không có JdbcTemplate. */
    private JdbcTemplate jdbcTemplate;

    public UsageLimits(@Value("${taskly.limits.active-projects:300}") int activeProjects,
                       @Value("${taskly.limits.projects:500}") int projects,
                       @Value("${taskly.limits.project-active-tasks:300}") int projectActiveTasks,
                       @Value("${taskly.limits.project-sections:20}") int projectSections,
                       @Value("${taskly.limits.project-sections-total:100}") int projectSectionsTotal,
                       @Value("${taskly.limits.labels:500}") int labels,
                       @Value("${taskly.limits.filters:150}") int filters,
                       @Value("${taskly.limits.reminders:700}") int reminders) {
        configured.put(LimitKind.ACTIVE_PROJECTS, activeProjects);
        configured.put(LimitKind.PROJECTS, projects);
        configured.put(LimitKind.PROJECT_ACTIVE_TASKS, projectActiveTasks);
        configured.put(LimitKind.PROJECT_SECTIONS, projectSections);
        configured.put(LimitKind.PROJECT_SECTIONS_TOTAL, projectSectionsTotal);
        configured.put(LimitKind.LABELS, labels);
        configured.put(LimitKind.FILTERS, filters);
        configured.put(LimitKind.REMINDERS, reminders);
    }

    public int max(LimitKind kind) {
        Integer override = overrides.get(kind);
        return override != null ? override : configured.get(kind);
    }

    /** currentCount + adding > max → 422. adding ≤ 0 (thao tác không làm tăng số đếm) không bao giờ ném. */
    public void check(LimitKind kind, long currentCount, long adding) {
        if (adding <= 0) {
            return;
        }
        int max = max(kind);
        if (currentCount + adding > max) {
            throw ApiException.unprocessable(ProblemTexts.LIMIT_REACHED)
                    .withCode(ProblemCodes.LIMIT_REACHED)
                    .withProperty("limit", kind.name())
                    .withProperty("max", max);
        }
    }

    /** CHỈ dùng trong test. */
    public void overrideMax(LimitKind kind, int max) {
        overrides.put(kind, max);
    }

    public void resetOverrides() {
        overrides.clear();
    }

    // ---- M2: label mỗi task (spec 2.1: vượt → 400, không phải 422; không khoá vì spec 8.2 #9 không liệt kê) ----
    @Value("${taskly.limits.labels-per-task:100}")
    private int maxLabelsPerTask;

    /** Số label của MỘT task sau thao tác. Vượt → 400 kèm thuộc tính `max` (spec 12.10). */
    public void checkLabelsPerTask(int count) {
        if (count > maxLabelsPerTask) {
            throw ApiException.badRequest(ProblemTexts.LABELS_PER_TASK).withProperty("max", maxLabelsPerTask);
        }
    }

    public int maxLabelsPerTask() {
        return maxLabelsPerTask;
    }

    @Autowired
    public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---- Khoá hàng cha chống đua (spec 8.2 #9) — gọi TRONG transaction, TRƯỚC khi đếm ----

    /** Đếm dự án (ACTIVE_PROJECTS, PROJECTS) của một workspace. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockWorkspace(UUID workspaceId) {
        lock("workspaces", workspaceId);
    }

    /** Đếm section / task đang mở của một dự án (PROJECT_SECTIONS*, PROJECT_ACTIVE_TASKS). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockProject(UUID projectId) {
        lock("projects", projectId);
    }

    /** Nhiều dự án đích (tasks/batch REOPEN, move nhiều nơi): khoá theo id TĂNG DẦN, bỏ trùng. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockProjects(Collection<UUID> projectIds) {
        projectIds.stream().distinct().sorted().forEach(this::lockProject);
    }

    /** Đếm theo tài khoản (M2 LABELS / FILTERS, M4 REMINDERS). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockUser(UUID userId) {
        lock("users", userId);
    }

    /** tableName chỉ là hằng nội bộ ở trên (không nhận từ người dùng). Hàng không tồn tại → không khoá gì (caller đã kiểm 404). */
    private void lock(String tableName, UUID id) {
        jdbcTemplate.queryForList("select id from " + tableName + " where id = ? for no key update", UUID.class, id);
    }
}
