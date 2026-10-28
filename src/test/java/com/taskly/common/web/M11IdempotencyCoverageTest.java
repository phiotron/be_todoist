package com.taskly.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.entity.LimitKind;
import com.taskly.service.UsageLimits;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M11: POST nhân bản / lưu template / áp template có Idempotency-Key; Move section thì không (không tạo gì). */
class M11IdempotencyCoverageTest {

    @Test
    void coversM11CreatePosts() {
        UUID id = UUID.randomUUID();
        assertThat(IdempotencyFilter.covers("/api/v1/projects/" + id + "/duplicate")).isTrue();
        assertThat(IdempotencyFilter.covers("/api/v1/projects/" + id + "/sections/" + id + "/duplicate")).isTrue();
        assertThat(IdempotencyFilter.covers("/api/v1/templates")).isTrue();
        assertThat(IdempotencyFilter.covers("/api/v1/templates/" + id + "/apply")).isTrue();
        assertThat(IdempotencyFilter.covers("/api/v1/templates/library/weekly-planning/apply")).isTrue();
    }

    @Test
    void doesNotCoverMoveSection() {
        UUID id = UUID.randomUUID();
        assertThat(IdempotencyFilter.covers("/api/v1/projects/" + id + "/sections/" + id + "/move")).isFalse();
    }

    @Test
    void m11LimitDefaults_whenBuiltByConstructor() {
        UsageLimits limits = new UsageLimits(300, 500, 300, 20, 100, 500, 150, 700);
        assertThat(limits.max(LimitKind.TEMPLATES)).isEqualTo(100);
        assertThat(limits.max(LimitKind.BACKUPS)).isEqualTo(21);
    }
}
