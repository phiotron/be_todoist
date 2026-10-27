package com.taskly.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Sau khi gộp M1: lỗi của endpoint M1 cũng dịch theo Accept-Language — không còn câu chỉ có tiếng Việt, không lộ key thô.
 * Không assert câu cụ thể (câu tiếng Việt do M1 chọn); chỉ assert vi khác en và không phải key.
 */
class M1ProblemLocalizationTest extends IntegrationTestBase {

    @Test
    void taskNotFound_detailIsTranslated() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String path = "/api/v1/tasks/" + UUID.randomUUID();

        String vi = JsonPath.read(body(mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isNotFound()).andReturn()), "$.detail");
        String en = JsonPath.read(body(mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isNotFound()).andReturn()), "$.detail");

        assertThat(vi).isNotBlank().doesNotStartWith("error.");
        assertThat(en).isNotBlank().doesNotStartWith("error.").isNotEqualTo(vi);
    }

    @Test
    void createTaskValidationErrors_areTranslated() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        List<String> vi = JsonPath.read(body(mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/tasks")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andReturn()), "$.errors[*].message");
        List<String> en = JsonPath.read(body(mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/tasks")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andReturn()), "$.errors[*].message");

        assertThat(vi).isNotEmpty().hasSameSizeAs(en);
        assertThat(en).doesNotContainAnyElementsOf(vi)
                .noneMatch(message -> message.startsWith("validation.") || message.startsWith("error."));
    }
}
