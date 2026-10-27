package com.taskly.dto.task;

/**
 * Body TUỲ CHỌN của POST tasks/{id}/reopen (spec 12.3). undo = true: Undo sau khi hoàn thành (snackbar 5 giây) — mở
 * lại cả con cháu có cùng completion_batch_id với task (spec 5.1). Vắng body / undo null = false.
 */
public record ReopenTaskRequest(Boolean undo) {

    public boolean isUndo() {
        return Boolean.TRUE.equals(undo);
    }
}
