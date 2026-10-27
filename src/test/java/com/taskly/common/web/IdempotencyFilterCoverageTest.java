package com.taskly.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Danh sách đường dẫn POST có Idempotency-Key (spec 12.11): tạo / sao chép / batch. Test đơn vị, không cần DB. */
class IdempotencyFilterCoverageTest {

    @Test
    void covers_createCopyAndBatchPosts() {
        assertThat(IdempotencyFilter.covers("/api/v1/projects")).isTrue();
        assertThat(IdempotencyFilter.covers("/api/v1/projects/" + UUID.randomUUID() + "/sections")).isTrue();
        assertThat(IdempotencyFilter.covers("/api/v1/tasks")).isTrue();
        assertThat(IdempotencyFilter.covers("/api/v1/tasks/" + UUID.randomUUID() + "/duplicate")).isTrue();
        assertThat(IdempotencyFilter.covers("/api/v1/tasks/batch")).isTrue(); // I6: batch MOVE / DELETE cần key
        assertThat(IdempotencyFilter.covers("/api/v1/labels")).isTrue(); // M2: id client + Idempotency-Key
        assertThat(IdempotencyFilter.covers("/api/v1/filters")).isTrue();
    }

    @Test
    void doesNotCover_otherEndpoints() {
        assertThat(IdempotencyFilter.covers("/api/v1/tasks/" + UUID.randomUUID() + "/close")).isFalse();
        assertThat(IdempotencyFilter.covers("/api/v1/tasks/" + UUID.randomUUID() + "/move")).isFalse();
        assertThat(IdempotencyFilter.covers("/api/v1/tasks/overdue/reschedule")).isFalse();
        assertThat(IdempotencyFilter.covers("/api/v1/projects/" + UUID.randomUUID() + "/archive")).isFalse();
        assertThat(IdempotencyFilter.covers("/api/v1/reorder")).isFalse();
        assertThat(IdempotencyFilter.covers("/api/v1/auth/login")).isFalse();
        assertThat(IdempotencyFilter.covers("/api/v1/filters/validate")).isFalse(); // chỉ đọc, không tạo gì
        assertThat(IdempotencyFilter.covers("/api/v1/labels/" + UUID.randomUUID())).isFalse();
    }
}
