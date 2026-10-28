package com.taskly.service.recurrence;

/** Chuỗi RRULE ngoài tập con của contract 8.1. Tầng service đổi thành 400 `recurrence-invalid`. Không mang câu chữ. */
public class InvalidRecurrenceException extends RuntimeException {

    public InvalidRecurrenceException() {
        super(null, null, false, false);
    }
}
