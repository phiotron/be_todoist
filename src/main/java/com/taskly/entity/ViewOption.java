package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/** Display đã lưu của một view (spec 10, 12.7): unique (user_id, view_key). */
@Entity
@Table(name = "view_options")
public class ViewOption extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "view_key", nullable = false, updatable = false, length = 64)
    private String viewKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "sort_by", nullable = false, length = 16)
    private SortBy sortBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "sort_direction", nullable = false, length = 4)
    private SortDirection sortDirection;

    @Enumerated(EnumType.STRING)
    @Column(name = "group_by", nullable = false, length = 16)
    private GroupBy groupBy;

    @Column(name = "filter_priority")
    private Integer filterPriority;

    @Column(name = "filter_label_id")
    private UUID filterLabelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "filter_due", length = 16)
    private DueFilter filterDue;

    @Column(name = "show_completed", nullable = false)
    private boolean showCompleted;

    protected ViewOption() {
        // cho JPA
    }

    public ViewOption(UUID userId, String viewKey) {
        this.userId = userId;
        this.viewKey = viewKey;
    }

    public void apply(SortBy sortBy, SortDirection sortDirection, GroupBy groupBy, Integer filterPriority,
                      UUID filterLabelId, DueFilter filterDue, boolean showCompleted) {
        this.sortBy = sortBy;
        this.sortDirection = sortDirection;
        this.groupBy = groupBy;
        this.filterPriority = filterPriority;
        this.filterLabelId = filterLabelId;
        this.filterDue = filterDue;
        this.showCompleted = showCompleted;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getViewKey() {
        return viewKey;
    }

    public SortBy getSortBy() {
        return sortBy;
    }

    public SortDirection getSortDirection() {
        return sortDirection;
    }

    public GroupBy getGroupBy() {
        return groupBy;
    }

    public Integer getFilterPriority() {
        return filterPriority;
    }

    public UUID getFilterLabelId() {
        return filterLabelId;
    }

    public DueFilter getFilterDue() {
        return filterDue;
    }

    public boolean isShowCompleted() {
        return showCompleted;
    }
}
