package com.taskly.service.display;

import com.taskly.common.error.ErrorMessage;
import com.taskly.support.Texts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ViewKeyTest {

    @Test
    void parsesFixedAndEntityKeys_andFormatsBack() {
        UUID id = UUID.randomUUID();

        assertThat(ViewKey.parse("TODAY")).isEqualTo(new ViewKey(ViewKind.TODAY, null));
        assertThat(ViewKey.parse("UPCOMING")).isEqualTo(new ViewKey(ViewKind.UPCOMING, null));
        assertThat(ViewKey.parse("project:" + id)).isEqualTo(new ViewKey(ViewKind.PROJECT, id));
        assertThat(ViewKey.parse("label:" + id)).isEqualTo(new ViewKey(ViewKind.LABEL, id));
        assertThat(ViewKey.parse("filter:" + id).format()).isEqualTo("filter:" + id);
        assertThat(ViewKey.parse("TODAY").format()).isEqualTo("TODAY");
        assertThat(ViewKey.parse("UPCOMING").format()).isEqualTo("UPCOMING");
        assertThat(ViewKey.parse("project:" + id).format()).isEqualTo("project:" + id);
    }

    @Test
    void malformedKeys_areRejectedWith400() {
        for (String bad : new String[] {null, "", "today", "project:", "project:not-a-uuid", "task:" + UUID.randomUUID(),
                "project:" + UUID.randomUUID().toString().toUpperCase(), "PROJECT:" + UUID.randomUUID(),
                "TODAY:" + UUID.randomUUID(), "TODAY ", " project:" + UUID.randomUUID(),
                "project:" + UUID.randomUUID() + "\n", "label:" + UUID.randomUUID() + "x"}) {
            assertThatThrownBy(() -> ViewKey.parse(bad)).as(String.valueOf(bad))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatusCode().value()).isEqualTo(400);
                        assertThat(ex.getErrorMessage()).isEqualTo(ErrorMessage.VIEW_KEY_INVALID);
                    });
        }
    }
}
