package com.taskly.common.error;

import java.util.Set;

/**
 * KEY (trong messages_*.properties) của title ProblemDetail theo mã HTTP và của detail mặc định cho lỗi do framework
 * sinh. Câu chữ vi / en nằm trong file properties, không nằm ở đây.
 */
final class ProblemTexts {

    /** Mã HTTP có title riêng; mã khác dùng problem.title.default. */
    static final Set<Integer> TITLED_STATUSES = Set.of(400, 401, 403, 404, 405, 409, 410, 415, 422, 429, 500, 503);

    /** Mã HTTP có detail mặc định riêng (thay câu tiếng Anh của Spring); mã khác dùng problem.detail.default. */
    static final Set<Integer> DEFAULT_DETAIL_STATUSES = Set.of(400, 403, 404, 405, 415, 429);

    private ProblemTexts() {
    }

    static String titleKey(int status) {
        return TITLED_STATUSES.contains(status) ? "problem.title." + status : "problem.title.default";
    }

    static String defaultDetailKey(int status) {
        return DEFAULT_DETAIL_STATUSES.contains(status) ? "problem.detail." + status : "problem.detail.default";
    }
}
