package com.taskly.service.sync;

import java.util.UUID;

/** Kết quả thành công của một lệnh; createdId khác null với lệnh tạo (để ghi tempIdMapping). */
public record SyncCommandOutcome(UUID createdId) {

    private static final SyncCommandOutcome OK = new SyncCommandOutcome(null);

    public static SyncCommandOutcome ok() {
        return OK;
    }

    public static SyncCommandOutcome created(UUID id) {
        return new SyncCommandOutcome(id);
    }
}
