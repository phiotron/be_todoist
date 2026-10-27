package com.taskly.service.filterquery;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;
import com.taskly.common.error.ProblemTexts;

/** Lỗi cú pháp filter: vị trí ký tự (0-based UTF-16 trong cả query) + mã lý do máy đọc được. */
public class FilterSyntaxException extends RuntimeException {

    private final int position;
    private final String reason;

    public FilterSyntaxException(int position, String reason) {
        super("Filter syntax error '" + reason + "' at " + position);
        this.position = position;
        this.reason = reason;
    }

    public int position() {
        return position;
    }

    public String reason() {
        return reason;
    }

    /** 400 ProblemDetail: code "filter-syntax", position, reason (spec 12.10). */
    public ApiException toApiException() {
        return ApiException.badRequest(ProblemTexts.FILTER_SYNTAX)
                .withCode(ProblemCodes.FILTER_SYNTAX)
                .withProperty("position", position)
                .withProperty("reason", reason);
    }
}
