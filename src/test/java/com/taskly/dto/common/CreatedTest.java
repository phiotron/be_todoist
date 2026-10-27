package com.taskly.dto.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class CreatedTest {

    @Test
    void fresh_is201_withoutReplayHeader() {
        ResponseEntity<String> response = Created.fresh("x").toResponseEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isEqualTo("x");
        assertThat(response.getHeaders().getFirst("Idempotent-Replay")).isNull();
    }

    @Test
    void replay_is200_withReplayHeader() {
        ResponseEntity<String> response = Created.replay("x").toResponseEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("x");
        assertThat(response.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
    }
}
