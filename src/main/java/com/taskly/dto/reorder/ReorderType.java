package com.taskly.dto.reorder;

/** M2 thêm LABEL, FILTER (spec 12.4); M8a thêm FOLDER (folder của workspace nhóm, chỉ Admin). */
public enum ReorderType {
    PROJECT,
    SECTION,
    TASK,
    LABEL,
    FILTER,
    FOLDER,
    /** DND: thứ tự trong NGÀY (Today / Upcoming, sort = date); vùng chứa = task mở cùng due_day. */
    TASK_DAY
}
