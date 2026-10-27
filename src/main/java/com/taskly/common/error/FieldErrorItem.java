package com.taskly.common.error;

/**
 * Một phần tử của "errors" trong ProblemDetail 400.
 * field = tên field JSON camelCase (email, password, displayName); message = câu đã dịch theo Accept-Language
 * (handler đặt key, ProblemDetailLocalizer dịch), app mobile hiển thị nguyên văn.
 */
public record FieldErrorItem(String field, String message) {
}
