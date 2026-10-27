package com.taskly.dto.filter;

import com.taskly.entity.ProjectColor;

/** Field vắng hoặc null = giữ nguyên. */
public record UpdateFilterRequest(String name, String query, ProjectColor color) {
}
