package com.taskly.service.display;

/** Tham số Display thô của GET tasks (chuỗi như client gửi, null = không gửi). */
public record DisplayQuery(String sort, String order, String group, String filterPriority, String filterLabelId,
                           String filterDue) {

    public boolean isEmpty() {
        return sort == null && order == null && group == null && filterPriority == null && filterLabelId == null
                && filterDue == null;
    }
}
