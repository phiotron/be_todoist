package com.taskly.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Nền cho test API M2: dữ liệu SQL (M2TestData) + helper gọi API có JWT + ngưỡng Pro hạ thấp (labels 5, filters 3).
 * Không thêm @Import / @MockitoBean → dùng chung Spring context + container với mọi test khác.
 */
public abstract class M2ApiTestBase extends IntegrationTestBase {

    protected M2TestData data;

    @BeforeEach
    void initM2Data() {
        data = new M2TestData(jdbcTemplate, clock);
        // IntegrationTestBase đã resetOverrides() ở @BeforeEach của lớp cha (chạy trước) → chỉ hạ ngưỡng cho test này.
        usageLimits.overrideMax(LimitKind.LABELS, 5);
        usageLimits.overrideMax(LimitKind.FILTERS, 3);
    }

    /** GET kèm tham số (cặp tên, giá trị). */
    protected ResultActions apiGet(String token, String path, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(path).header(HttpHeaders.AUTHORIZATION, bearer(token));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request);
    }

    /** POST; json null = không có body. */
    protected ResultActions apiPost(String token, String path, String json) throws Exception {
        MockHttpServletRequestBuilder request = post(path).header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    protected ResultActions apiPostWithKey(String token, String path, String key, String json) throws Exception {
        return mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions apiPatch(String token, String path, String json) throws Exception {
        return mockMvc.perform(patch(path).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** PUT; json null = không có body. */
    protected ResultActions apiPut(String token, String path, String json) throws Exception {
        MockHttpServletRequestBuilder request = put(path).header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    protected ResultActions apiDelete(String token, String path) throws Exception {
        return mockMvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    protected String okBody(ResultActions actions) throws Exception {
        return body(actions.andExpect(status().isOk()).andReturn());
    }

    protected String getOk(String token, String path, String... params) throws Exception {
        return okBody(apiGet(token, path, params));
    }

    protected static UUID readId(ResultActions actions) throws Exception {
        return UUID.fromString(JsonPath.read(body(actions.andReturn()), "$.id"));
    }

    protected static List<String> contents(String json) {
        return JsonPath.read(json, "$.items[*].content");
    }

    protected static List<String> ids(String json) {
        return JsonPath.read(json, "$.items[*].id");
    }

    protected static String nextCursor(String json) {
        return JsonPath.read(json, "$.nextCursor");
    }

    /** Đi hết các trang với limit nhỏ, gom content theo thứ tự; trang vượt limit hoặc quá 100 trang → fail. */
    protected List<String> collectAllContents(String token, String path, int limit, String... params)
            throws Exception {
        List<String> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 100; page++) {
            List<String> pageParams = new ArrayList<>(List.of(params));
            pageParams.addAll(List.of("limit", String.valueOf(limit)));
            if (cursor != null) {
                pageParams.addAll(List.of("cursor", cursor));
            }
            String json = getOk(token, path, pageParams.toArray(String[]::new));
            List<String> pageContents = contents(json);
            if (pageContents.size() > limit) {
                throw new AssertionError("Trang có " + pageContents.size() + " phần tử, vượt limit " + limit);
            }
            all.addAll(pageContents);
            cursor = nextCursor(json);
            if (cursor == null) {
                return all;
            }
        }
        throw new AssertionError("Quá 100 trang — con trỏ không tiến");
    }
}
