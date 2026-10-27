package com.taskly.service.display;

import com.taskly.entity.DueFilter;
import com.taskly.entity.GroupBy;
import com.taskly.entity.SortBy;
import com.taskly.entity.SortDirection;
import java.util.UUID;

/** Display đã kiểm hợp lệ cho một view. filter* null = không lọc. */
public record DisplayOptions(SortBy sortBy, SortDirection direction, GroupBy groupBy, Integer filterPriority,
                             UUID filterLabelId, DueFilter filterDue) {
}
