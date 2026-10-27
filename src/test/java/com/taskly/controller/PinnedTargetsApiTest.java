package com.taskly.controller;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Id ghim trong startPage / navTabs phải đọc được (spec 12.1.1); dự án tạo bằng API M1 (spec 12.2). */
class PinnedTargetsApiTest extends IntegrationTestBase {

    private static final String SETTINGS = "/api/v1/me/settings";
    private static final String UNAVAILABLE =
            "Dự án, nhãn hoặc bộ lọc được chọn không còn tồn tại hoặc không thuộc tài khoản này";

    private String createProject(String accessToken) throws Exception {
        return JsonPath.read(body(mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Dự án ghim\"}"))
                .andExpect(status().isCreated())
                .andReturn()), "$.id");
    }

    private ResultActions patchSettings(String accessToken, String json) throws Exception {
        return mockMvc.perform(patch(SETTINGS).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void ownProject_isAcceptedInStartPageAndNavTabs() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String pinned = "project:" + createProject(user.accessToken());

        patchSettings(user.accessToken(), """
                {"startPage": "%s", "navTabs": ["TODAY", "%s", "BROWSE"]}
                """.formatted(pinned, pinned))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startPage").value(pinned))
                .andExpect(jsonPath("$.navTabs", contains("TODAY", pinned, "BROWSE")));
    }

    @Test
    void anotherUsersProject_inStartPage_returns400WithFieldError() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser other = registerUser(uniqueEmail());
        String foreign = "project:" + createProject(owner.accessToken());

        patchSettings(other.accessToken(), "{\"startPage\": \"%s\"}".formatted(foreign))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Dữ liệu gửi lên không hợp lệ"))
                .andExpect(jsonPath("$.errors[?(@.field == 'startPage')].message", contains(UNAVAILABLE)));
    }

    @Test
    void anotherUsersProject_inNavTabs_returns400WithFieldError() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser other = registerUser(uniqueEmail());
        String foreign = "project:" + createProject(owner.accessToken());

        patchSettings(other.accessToken(), "{\"navTabs\": [\"INBOX\", \"%s\", \"BROWSE\"]}".formatted(foreign))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'navTabs')].message", contains(UNAVAILABLE)));
    }

    @Test
    void unknownLabelAndFilterIds_areRejected() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String label = "label:" + UUID.randomUUID();
        String filter = "filter:" + UUID.randomUUID();

        patchSettings(user.accessToken(), "{\"startPage\": \"%s\"}".formatted(label))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'startPage')].message", contains(UNAVAILABLE)));
        patchSettings(user.accessToken(), "{\"navTabs\": [\"TODAY\", \"%s\", \"BROWSE\"]}".formatted(filter))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'navTabs')].message", contains(UNAVAILABLE)));
    }

    @Test
    void searchAndFiltersLabelsTabs_areAcceptedAfterM2() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        patchSettings(user.accessToken(), "{\"navTabs\": [\"INBOX\", \"SEARCH\", \"BROWSE\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.navTabs", contains("INBOX", "SEARCH", "BROWSE")));
        patchSettings(user.accessToken(), "{\"navTabs\": [\"INBOX\", \"FILTERS_LABELS\", \"BROWSE\"]}")
                .andExpect(status().isOk());
    }

    private String createNamed(String accessToken, String path, String json) throws Exception {
        return JsonPath.read(body(mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn()), "$.id");
    }

    @Test
    void ownLabelAndFilter_areAcceptedInNavTabsAndStartPage() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String label = "label:" + createNamed(user.accessToken(), "/api/v1/labels", "{\"name\": \"ghim\"}");
        String filter = "filter:" + createNamed(user.accessToken(), "/api/v1/filters",
                "{\"name\": \"Gấp\", \"query\": \"p1\"}");

        patchSettings(user.accessToken(), "{\"startPage\": \"%s\", \"navTabs\": [\"TODAY\", \"%s\", \"BROWSE\"]}"
                .formatted(label, label))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startPage").value(label))
                .andExpect(jsonPath("$.navTabs", contains("TODAY", label, "BROWSE")));
        patchSettings(user.accessToken(), "{\"startPage\": \"%s\", \"navTabs\": [\"TODAY\", \"%s\", \"BROWSE\"]}"
                .formatted(filter, filter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startPage").value(filter));
    }

    @Test
    void anotherUsersLabelAndFilter_areRejected() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser other = registerUser(uniqueEmail());
        String label = "label:" + createNamed(owner.accessToken(), "/api/v1/labels", "{\"name\": \"rieng\"}");
        String filter = "filter:" + createNamed(owner.accessToken(), "/api/v1/filters",
                "{\"name\": \"Rieng\", \"query\": \"p1\"}");

        patchSettings(other.accessToken(), "{\"startPage\": \"%s\"}".formatted(label))
                .andExpect(status().isBadRequest());
        patchSettings(other.accessToken(), "{\"navTabs\": [\"TODAY\", \"%s\", \"BROWSE\"]}".formatted(filter))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectedPatch_changesNothingAtAll() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        patchSettings(user.accessToken(), """
                {"theme": "KALE", "startPage": "project:%s"}
                """.formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get(SETTINGS).header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(jsonPath("$.theme").value("TASKLY"))
                .andExpect(jsonPath("$.startPage").value("TODAY"));
    }

    @Test
    void projectDeletedAfterPinning_settingStaysStored_andFieldsNotSentAreNotRechecked() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String projectId = createProject(user.accessToken());
        String pinned = "project:" + projectId;
        patchSettings(user.accessToken(), "{\"startPage\": \"%s\"}".formatted(pinned)).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/projects/" + projectId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(SETTINGS).header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(jsonPath("$.startPage").value(pinned));
        patchSettings(user.accessToken(), "{\"theme\": \"KALE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startPage").value(pinned));
        patchSettings(user.accessToken(), "{\"startPage\": \"%s\"}".formatted(pinned))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unavailablePin_messageInEnglish() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patch(SETTINGS).header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startPage\": \"project:%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'startPage')].message", contains(
                        "The selected project, label or filter no longer exists or does not belong to this account")));
    }
}
