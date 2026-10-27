package com.taskly.repository.listing;

/** Một khoá sắp: biểu thức SQL (alias t = tasks, p = projects, s = sections…) KHÔNG null, kiểu, chiều. */
public record SortKey(String expression, KeyKind kind, boolean descending) {
}
