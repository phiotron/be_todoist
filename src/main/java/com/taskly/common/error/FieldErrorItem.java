package com.taskly.common.error;

/**
 * Một phần tử của "errors" trong ProblemDetail 400.
 * field = tên field JSON camelCase (email, password, displayName); message = câu tiếng Việt đặt trên annotation DTO,
 * app mobile hiển thị nguyên văn.
 */
public record FieldErrorItem(String field, String message) {
}
