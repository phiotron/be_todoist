package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import com.taskly.common.error.ApiException;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class UserWorkspaceLookupTest extends IntegrationTestBase {

    @Autowired private UserService userService;
    @Autowired private WorkspaceService workspaceService;

    @Test
    void timeZoneOf_readsUserTimeZone() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        assertThat(userService.timeZoneOf(user.id())).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));

        testData.setTimeZone(user.id(), "America/New_York");
        assertThat(userService.timeZoneOf(user.id())).isEqualTo(ZoneId.of("America/New_York"));
    }

    @Test
    void timeZoneOf_unknownUser_is401() {
        assertThatThrownBy(() -> userService.timeZoneOf(UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(401));
    }

    @Test
    void personalWorkspace_returnsTheUsersPersonalWorkspace() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        assertThat(workspaceService.personalWorkspace(user.id()).getId()).isEqualTo(testData.workspaceOf(user.id()));
        assertThat(testData.inboxOf(user.id())).isNotNull();
    }
}
