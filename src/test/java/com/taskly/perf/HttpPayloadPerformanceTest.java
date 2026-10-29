package com.taskly.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import com.taskly.support.M2TestData;
import com.taskly.support.TestData.TaskRow;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * PERF-NET: đo kích thước JSON (byte, số trường) và p50/p95 của các endpoint chính với 300 task, cùng chi phí giải mã JWT.
 * Chạy riêng: .\mvnw test "-Dtest=HttpPayloadPerformanceTest" "-Dtaskly.perf=true"
 */
@Tag("perf")
@EnabledIfSystemProperty(named = "taskly.perf", matches = "true")
class HttpPayloadPerformanceTest extends IntegrationTestBase {

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void measurePayloadsAndLatency() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        M2TestData m2 = new M2TestData(jdbcTemplate, clock);
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        UUID inbox = testData.inboxOf(user.id());
        UUID project = testData.project(user.id(), "Dự án đo");
        List<UUID> labels = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            labels.add(m2.label(user.id(), "nhan" + i));
        }
        for (int i = 0; i < 20; i++) {
            testData.project(user.id(), "Dự án phụ " + i);
        }
        for (int i = 0; i < 300; i++) {
            UUID target = i % 2 == 0 ? inbox : project;
            UUID id = testData.insert(TaskRow.of(user.id(), target, "Việc số " + i).priority(1 + i % 4)
                    .dueDate(i % 3 == 0 ? today : today.plusDays(i % 9)));
            if (i % 2 == 0) {
                m2.tag(id, labels.get(i % 4));
            }
        }
        // 300 task: 150 ở project, nhiều task có due hôm nay; today view lấy 50
        record Case(String name, String uri) { }
        List<Case> cases = List.of(
                new Case("tasks?view=today", "/api/v1/tasks?view=today&limit=50"),
                new Case("tasks?projectId=", "/api/v1/tasks?projectId=" + project + "&limit=50"),
                new Case("projects", "/api/v1/projects"),
                new Case("labels", "/api/v1/labels"));
        for (Case c : cases) {
            measure(user, c.name(), () -> authGet(user.accessToken(), c.uri()).andExpect(status().isOk()).andReturn());
        }
        measure(user, "sync (full)", () -> mockMvc.perform(post("/api/v1/sync")
                .header("Authorization", bearer(user.accessToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"syncToken\":\"*\",\"commands\":[]}"))
                .andExpect(status().isOk()).andReturn());

        // chi phí giải mã JWT HS256 mỗi request
        for (int i = 0; i < 500; i++) {
            jwtDecoder.decode(user.accessToken());
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < 2000; i++) {
            jwtDecoder.decode(user.accessToken());
        }
        System.out.printf("[net] JWT decode: %.1f us / lần%n", (System.nanoTime() - t0) / 2000 / 1000.0);
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private interface Call {
        MvcResult run() throws Exception;
    }

    private void measure(RegisteredUser user, String name, Call call) throws Exception {
        for (int i = 0; i < 15; i++) {
            call.run();
        }
        List<Long> times = new ArrayList<>();
        MvcResult last = null;
        for (int i = 0; i < 60; i++) {
            long s = System.nanoTime();
            last = call.run();
            times.add((System.nanoTime() - s) / 1000);
        }
        Collections.sort(times);
        String json = body(last);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        // đếm khoá JSON, null, mảng rỗng
        int keys = count(json, "\":");
        int nulls = count(json, ":null");
        int emptyArrays = count(json, ":[]");
        System.out.printf("[net] %-20s bytes=%d keys=%d null=%d mangRong=%d p50=%.1fms p95=%.1fms%n", name, bytes, keys,
                nulls, emptyArrays, times.get(30) / 1000.0, times.get(56) / 1000.0);
        assertThat(JsonPath.<Object>read(json, "$")).isNotNull();
    }
}
