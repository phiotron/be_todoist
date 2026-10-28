package com.taskly.security;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Scope của token OAuth cho ứng dụng bên thứ ba (kiểu Todoist) và luật "scope nào gọi được request nào" (plan M13c, F3).
 * Thuần (không Spring bean) để test bảng. data:delete ⊃ data:read_write ⊃ data:read; task:add chỉ tạo task.
 */
public final class OAuthScopes {

    public static final String TASK_ADD = "task:add";
    public static final String DATA_READ = "data:read";
    public static final String DATA_READ_WRITE = "data:read_write";
    public static final String DATA_DELETE = "data:delete";
    public static final String PROJECT_DELETE = "project:delete";

    public static final Set<String> ALL = Set.of(TASK_ADD, DATA_READ, DATA_READ_WRITE, DATA_DELETE, PROJECT_DELETE);

    /** typ của Jwt dựng từ token OAuth (không phải typ "access" của app — decoder JWT của app không nhận). */
    public static final String TOKEN_TYPE = "oauth-access";

    private static final List<PathPattern> OPEN_PATHS = pathPatterns(
            "/api/v1/projects/**", "/api/v1/tasks/**", "/api/v1/labels/**", "/api/v1/filters/**",
            "/api/v1/search/**", "/api/v1/favorites", "/api/v1/reorder");
    private static final PathPattern ME = parse("/api/v1/me");
    private static final PathPattern TASKS_ROOT = parse("/api/v1/tasks");
    private static final PathPattern PROJECT_ITEM = parse("/api/v1/projects/{id}");

    private OAuthScopes() {
    }

    private static List<PathPattern> pathPatterns(String... patterns) {
        return Arrays.stream(patterns).map(OAuthScopes::parse).toList();
    }

    private static PathPattern parse(String pattern) {
        return PathPatternParser.defaultInstance.parse(pattern);
    }

    /** Tách scope theo dấu phẩy / khoảng trắng; rỗng, trùng được gộp; scope lạ hoặc không có scope nào → 400. */
    public static Set<String> parseRequested(String raw) {
        Set<String> scopes = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split("[,\s]+")) {
                if (!part.isEmpty()) {
                    scopes.add(part);
                }
            }
        }
        if (scopes.isEmpty() || !ALL.containsAll(scopes)) {
            throw ApiException.badRequest(ErrorMessage.OAUTH_SCOPE_INVALID).withCode(ProblemCodes.OAUTH_INVALID_SCOPE);
        }
        return scopes;
    }

    /** Scope lưu trong DB: cách nhau một khoảng trắng. */
    public static Set<String> fromStored(String stored) {
        return new LinkedHashSet<>(Arrays.asList(stored.split(" ")));
    }

    public static String toStored(Set<String> scopes) {
        return String.join(" ", scopes);
    }

    /** Token có được gọi (method, path) không? Endpoint ngoài danh sách mở cho OAuth → false (403 như thiếu scope). */
    public static boolean allows(Set<String> scopes, String method, String path) {
        PathContainer container = PathContainer.parsePath(path);
        boolean isMe = ME.matches(container) && HttpMethod.GET.matches(method);
        if (!isMe && OPEN_PATHS.stream().noneMatch(pattern -> pattern.matches(container))) {
            return false;
        }
        boolean read = scopes.contains(DATA_READ) || scopes.contains(DATA_READ_WRITE) || scopes.contains(DATA_DELETE);
        boolean write = scopes.contains(DATA_READ_WRITE) || scopes.contains(DATA_DELETE);
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)) {
            return read;
        }
        if (isMe) {
            return false;
        }
        if (HttpMethod.DELETE.matches(method)) {
            if (PROJECT_ITEM.matches(container)) {
                return scopes.contains(PROJECT_DELETE);
            }
            return scopes.contains(DATA_DELETE);
        }
        if (HttpMethod.POST.matches(method) && TASKS_ROOT.matches(container)) {
            return write || scopes.contains(TASK_ADD);
        }
        return write;
    }
}
