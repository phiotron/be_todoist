package com.taskly.entity;

/** Hành động của POST tasks/batch (spec 12.5). M2 thêm ADD_LABELS, REMOVE_LABELS. */
public enum BatchAction {
    CLOSE,
    REOPEN,
    MOVE,
    DELETE,
    SCHEDULE,
    PRIORITY
}
