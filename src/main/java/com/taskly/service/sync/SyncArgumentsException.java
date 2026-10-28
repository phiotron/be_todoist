package com.taskly.service.sync;

import com.taskly.common.error.CommandProblems;
import com.taskly.common.error.FieldErrorItem;
import java.util.List;

/** Args của lệnh không qua Bean Validation / sai kiểu: 400 VALIDATION_FAILED kèm errors[] (message là KEY, sẽ dịch). */
public class SyncArgumentsException extends RuntimeException implements CommandProblems.FieldErrors {

    private final transient List<FieldErrorItem> errors;

    public SyncArgumentsException(List<FieldErrorItem> errors) {
        super("sync arguments invalid");
        this.errors = List.copyOf(errors);
    }

    @Override
    public List<FieldErrorItem> errors() {
        return errors;
    }
}
