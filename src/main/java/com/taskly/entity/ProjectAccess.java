package com.taskly.entity;

/** M8a: quyền truy cập dự án của workspace nhóm (spec 11.2). Dự án cá nhân để null. */
public enum ProjectAccess {
    /** Mọi Admin / Member của team thấy và tự tham gia được. */
    TEAM,
    /** Chỉ người được mời (kể cả Admin không được mời cũng không thấy — plan M8a câu hỏi 6). */
    RESTRICTED
}
