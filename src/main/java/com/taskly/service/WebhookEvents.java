package com.taskly.service;

import com.taskly.entity.BatchAction;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Danh mục sự kiện webhook + ánh xạ request REST thành công, ra tên sự kiện (plan M13c, F5). Thuần, test bảng.
 * Khoá ánh xạ = "METHOD mẫu đường dẫn" với {tên biến} đã bỏ tên.
 */
public final class WebhookEvents {

    public static final Set<String> ALL = Set.of(
            "item:added", "item:updated", "item:deleted", "item:completed", "item:uncompleted",
            "project:added", "project:updated", "project:deleted", "project:archived", "project:unarchived",
            "section:added", "section:updated", "section:deleted", "section:archived", "section:unarchived",
            "label:added", "label:updated", "label:deleted",
            "filter:added", "filter:updated", "filter:deleted");

    /** Sự kiện tạo mới chỉ phát khi 201 (200 = phát lại theo id client, không phải tạo mới). */
    private static final Set<String> CREATE_EVENTS = Set.of(
            "item:added", "project:added", "section:added", "label:added", "filter:added");

    private static final Pattern VARIABLE = Pattern.compile("\\{[^}]+}");

    private static final Map<String, String> ROUTES = Map.ofEntries(
            Map.entry("POST /api/v1/tasks", "item:added"),
            Map.entry("POST /api/v1/tasks/{}/duplicate", "item:added"),
            Map.entry("PATCH /api/v1/tasks/{}", "item:updated"),
            Map.entry("DELETE /api/v1/tasks/{}", "item:deleted"),
            Map.entry("POST /api/v1/tasks/{}/close", "item:completed"),
            Map.entry("POST /api/v1/tasks/{}/reopen", "item:uncompleted"),
            Map.entry("POST /api/v1/projects", "project:added"),
            Map.entry("PATCH /api/v1/projects/{}", "project:updated"),
            Map.entry("DELETE /api/v1/projects/{}", "project:deleted"),
            Map.entry("POST /api/v1/projects/{}/archive", "project:archived"),
            Map.entry("POST /api/v1/projects/{}/unarchive", "project:unarchived"),
            Map.entry("POST /api/v1/projects/{}/sections", "section:added"),
            Map.entry("PATCH /api/v1/projects/{}/sections/{}", "section:updated"),
            Map.entry("DELETE /api/v1/projects/{}/sections/{}", "section:deleted"),
            Map.entry("POST /api/v1/projects/{}/sections/{}/archive", "section:archived"),
            Map.entry("POST /api/v1/projects/{}/sections/{}/unarchive", "section:unarchived"),
            Map.entry("POST /api/v1/labels", "label:added"),
            Map.entry("PATCH /api/v1/labels/{}", "label:updated"),
            Map.entry("DELETE /api/v1/labels/{}", "label:deleted"),
            Map.entry("POST /api/v1/filters", "filter:added"),
            Map.entry("PATCH /api/v1/filters/{}", "filter:updated"),
            Map.entry("DELETE /api/v1/filters/{}", "filter:deleted"));

    private WebhookEvents() {
    }

    /** Request (method, mẫu đường dẫn Spring, mã HTTP) → tên sự kiện; rỗng nếu không phải sự kiện webhook. */
    public static Optional<String> resolve(String method, String pathPattern, int status) {
        if (pathPattern == null || status < 200 || status > 299) {
            return Optional.empty();
        }
        String event = ROUTES.get(method + " " + VARIABLE.matcher(pathPattern).replaceAll("{}"));
        if (event == null || (CREATE_EVENTS.contains(event) && status != 201)) {
            return Optional.empty();
        }
        return Optional.of(event);
    }

    /**
     * Sự kiện của POST tasks/batch theo action (mỗi task trong lô một sự kiện, như thao tác đơn lẻ tương ứng):
     * CLOSE → item:completed, REOPEN → item:uncompleted, DELETE → item:deleted, các action còn lại đổi task → item:updated.
     */
    public static String batchEvent(BatchAction action) {
        return switch (action) {
            case CLOSE -> "item:completed";
            case REOPEN -> "item:uncompleted";
            case DELETE -> "item:deleted";
            case MOVE, SCHEDULE, PRIORITY, ADD_LABELS, REMOVE_LABELS -> "item:updated";
        };
    }

    /** Tên biến đường dẫn chứa id của đối tượng (dùng khi response không có body, vd DELETE). */
    public static String idVariable(String event) {
        return switch (event.substring(0, event.indexOf(':'))) {
            case "item" -> "taskId";
            case "project" -> "projectId";
            case "section" -> "sectionId";
            case "label" -> "labelId";
            default -> "filterId";
        };
    }
}
