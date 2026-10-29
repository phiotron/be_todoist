package com.taskly.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * PERF-NET: Tomcat thật (cổng ngẫu nhiên) nén JSON ≥ 1 KB khi client gửi Accept-Encoding: gzip, và không nén bản nhỏ
 * hay khi client không xin.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResponseCompressionTest extends IntegrationTestBase {

    @LocalServerPort
    private int port;

    private HttpResponse<byte[]> get(String path, String token, boolean gzip) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token);
        if (gzip) {
            builder.header("Accept-Encoding", "gzip");
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void largeJsonIsGzippedOnlyWhenAsked() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        for (int i = 0; i < 40; i++) {
            testData.task(user.id(), inbox, "Việc số " + i);
        }
        String path = "/api/v1/tasks?projectId=" + inbox + "&limit=50";

        HttpResponse<byte[]> plain = get(path, user.accessToken(), false);
        HttpResponse<byte[]> zipped = get(path, user.accessToken(), true);

        assertThat(plain.statusCode()).isEqualTo(200);
        assertThat(plain.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(zipped.headers().firstValue("Content-Encoding")).contains("gzip");
        String unzipped = new String(new GZIPInputStream(new ByteArrayInputStream(zipped.body())).readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(unzipped).isEqualTo(new String(plain.body(), StandardCharsets.UTF_8));
        System.out.printf("[net] gzip tasks 40: %d -> %d byte%n", plain.body().length, zipped.body().length);

        // nhỏ hơn 1 KB: không nén
        HttpResponse<byte[]> small = get("/api/v1/labels", user.accessToken(), true);
        assertThat(small.body().length).isLessThan(1024);
        assertThat(small.headers().firstValue("Content-Encoding")).isEmpty();
    }
}
