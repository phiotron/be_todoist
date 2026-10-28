package com.taskly.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;
import com.taskly.service.sync.SyncCommandHandler;
import com.taskly.service.sync.SyncCommandOutcome;
import com.taskly.service.sync.SyncCommandRegistry;
import com.taskly.service.sync.SyncToken;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phần thuần của M9: SyncToken, SyncCommandRegistry (điểm cắm loại lệnh — plan mục 8.3). */
class SyncUnitTest {

    @Test
    void token_roundTrips() {
        SyncToken token = new SyncToken(12345L, Instant.ofEpochSecond(1_700_000_000L));
        assertThat(token.format()).isEqualTo("12345.1700000000");
        assertThat(SyncToken.parse(token.format())).isEqualTo(token);
    }

    @Test
    void token_fullSyncForNullBlankOrStar() {
        assertThat(SyncToken.parse(null)).isNull();
        assertThat(SyncToken.parse("")).isNull();
        assertThat(SyncToken.parse("  ")).isNull();
        assertThat(SyncToken.parse("*")).isNull();
    }

    @Test
    void token_malformedIs400() {
        for (String bad : new String[] {"abc", "1", "1.", ".2", "1.2.3", "-1.5", "99999999999999999999.1", "1.1234567890123"}) {
            assertThatThrownBy(() -> SyncToken.parse(bad)).as(bad).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getBody().getStatus()).isEqualTo(400));
        }
    }

    @Test
    void registry_findsHandlersByType_andRejectsDuplicates() {
        SyncCommandHandler a = SyncCommandHandler.of("a_cmd", ctx -> SyncCommandOutcome.ok());
        SyncCommandHandler b = SyncCommandHandler.creating("b_cmd", ctx -> SyncCommandOutcome.ok());
        SyncCommandRegistry registry = new SyncCommandRegistry(List.of(a, b));

        assertThat(registry.find("a_cmd")).containsSame(a);
        assertThat(registry.find("b_cmd").orElseThrow().creates()).isTrue();
        assertThat(registry.find("c_cmd")).isEmpty();
        assertThat(registry.types()).containsExactly("a_cmd", "b_cmd");
        assertThatThrownBy(() -> new SyncCommandRegistry(List.of(a, a)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("a_cmd");
    }
}
