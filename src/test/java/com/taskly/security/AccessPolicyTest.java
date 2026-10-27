package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import com.taskly.common.error.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AccessPolicyTest extends IntegrationTestBase {

    @Autowired
    private AccessPolicy accessPolicy;

    private UUID workspaceOf(UUID userId) {
        return jdbcTemplate.queryForObject(
                "select id from workspaces where owner_id = ? and type = 'PERSONAL'", UUID.class, userId);
    }

    @Test
    void memberCanReadAndWriteOwnWorkspace() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        UUID workspace = workspaceOf(alice.id());

        assertThat(accessPolicy.canRead(alice.id(), workspace)).isTrue();
        assertThatCode(() -> accessPolicy.checkRead(alice.id(), workspace, "Không tìm thấy dự án"))
                .doesNotThrowAnyException();
        assertThatCode(() -> accessPolicy.checkWrite(alice.id(), workspace, "Không tìm thấy dự án"))
                .doesNotThrowAnyException();
        assertThat(accessPolicy.readableWorkspaceIds(alice.id())).containsExactly(workspace);
    }

    @Test
    void outsider_gets404WithGivenDetail_neverLearnsTheWorkspaceExists() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID aliceWorkspace = workspaceOf(alice.id());

        assertThat(accessPolicy.canRead(bob.id(), aliceWorkspace)).isFalse();
        assertThatThrownBy(() -> accessPolicy.checkWrite(bob.id(), aliceWorkspace, "Không tìm thấy công việc"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(404);
                    assertThat(ex.getBody().getDetail()).isEqualTo("Không tìm thấy công việc");
                });
        assertThat(accessPolicy.readableWorkspaceIds(bob.id())).doesNotContain(aliceWorkspace);
    }
}
