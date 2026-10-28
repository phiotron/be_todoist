package com.taskly.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Hợp đồng OpenAPI của M8a — mobile khai báo tay kiểu theo đúng tên DTO này tới khi sinh lại types.gen.ts. */
class OpenApiM8aDocsTest extends IntegrationTestBase {

    private ResultActions docs() throws Exception {
        return mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void m8aEndpointsAreDocumentedWithBearerAuth() throws Exception {
        docs()
                .andExpect(jsonPath("$.paths['/api/v1/workspaces'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/workspaces'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/members'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/projects'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/folders'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/invite-link'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/members'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/invitations'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/invite-link'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/leave'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/join'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/move-to-workspace'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/invitations'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/invitations/accept'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/invitations/{invitationId}/decline'].post").exists());
    }

    @Test
    void m8aSchemasMarkEveryResponseFieldRequired() throws Exception {
        docs()
                .andExpect(jsonPath("$.components.schemas.WorkspaceResponse.required")
                        .value(containsInAnyOrder("id", "name", "type", "role", "memberCount")))
                .andExpect(jsonPath("$.components.schemas.TeamProjectResponse.required").value(containsInAnyOrder(
                        "id", "name", "color", "folderId", "access", "memberCount", "activeTaskCount", "joined")))
                .andExpect(jsonPath("$.components.schemas.ProjectMemberResponse.required")
                        .value(containsInAnyOrder("userId", "email", "displayName", "isOwner", "avatarUrl")))
                .andExpect(jsonPath("$.components.schemas.WorkspaceMemberResponse.required")
                        .value(containsInAnyOrder("userId", "email", "displayName", "role", "joinedAt", "avatarUrl")))
                .andExpect(jsonPath("$.components.schemas.InviteLinkResponse.required")
                        .value(containsInAnyOrder("token", "url")))
                .andExpect(jsonPath("$.components.schemas.AcceptInvitationResponse.required")
                        .value(containsInAnyOrder("kind", "projectId", "workspaceId")))
                .andExpect(jsonPath("$.components.schemas.ProjectResponse.required").value(hasItems(
                        "workspaceId", "folderId", "access", "memberCount", "joined", "canManage")))
                .andExpect(jsonPath("$.components.schemas.SettingsResponse.required").value(hasItems("autoAcceptInvites")));
    }

    @Test
    void m8aEnumsAreDocumented() throws Exception {
        docs()
                .andExpect(jsonPath("$.components.schemas.WorkspaceResponse.properties.role.enum")
                        .value(containsInAnyOrder("ADMIN", "MEMBER", "GUEST")))
                .andExpect(jsonPath("$.components.schemas.WorkspaceResponse.properties.type.enum")
                        .value(containsInAnyOrder("PERSONAL", "TEAM")))
                .andExpect(jsonPath("$.components.schemas.TeamProjectResponse.properties.access.enum")
                        .value(containsInAnyOrder("TEAM", "RESTRICTED")));
    }
}
