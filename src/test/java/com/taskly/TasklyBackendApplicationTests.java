package com.taskly;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

class TasklyBackendApplicationTests extends IntegrationTestBase {

    @Autowired
    private Clock injectedClock;

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
    }

    @Test
    void injectedClockIsTheTestClock() {
        // Trong test, MutableClock (@Primary) thay Clock thật để dời được "bây giờ".
        assertThat(injectedClock).isSameAs(clock);
    }

    @Test
    void openInViewIsDisabledAndTestProfileIsActive() {
        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(environment.getActiveProfiles()).contains("test");
    }
}
