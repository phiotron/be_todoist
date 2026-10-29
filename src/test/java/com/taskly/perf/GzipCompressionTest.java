package com.taskly.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.support.M8aApiTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * PERF (perf2): JSON lớn (full sync ~640 KB, danh sách ~40 KB) được nén gzip khi client gửi Accept-Encoding: gzip;
 * JSON nhỏ (< 1 KB) và request không xin gzip giữ nguyên. Cần Tomcat thật (MockMvc không có tầng nén) → RANDOM_PORT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GzipCompressionTest extends M8aApiTestBase {

    @LocalServerPort
    int port;

    private HttpResponse<byte[]> get(String token, String path, String acceptEncoding) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token);
        if (acceptEncoding != null) {
            request.header("Accept-Encoding", acceptEncoding);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void largeJsonIsGzippedWhenClientAsksForIt_andPlainOtherwise() throws Exception {
        RegisteredUser user = newUser();
        UUID project = createProject(user, "Nen");
        for (int i = 0; i < 30; i++) {
            createOverdueTask(user, project, "viec so " + i);
        }
        String token = user.accessToken();

        HttpResponse<byte[]> gzipped = get(token, "/api/v1/tasks?view=today&limit=50", "gzip");
        assertThat(gzipped.statusCode()).isEqualTo(200);
        assertThat(gzipped.headers().firstValue("Content-Encoding")).contains("gzip");

        HttpResponse<byte[]> plain = get(token, "/api/v1/tasks?view=today&limit=50", null);
        assertThat(plain.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(gzipped.body().length).as("gzip nhỏ hơn bản thô ≥ 3 lần").isLessThan(plain.body().length / 3);

        HttpResponse<byte[]> tiny = get(token, "/api/v1/me/settings", "gzip"); // < 1 KB → không nén
        assertThat(tiny.statusCode()).isEqualTo(200);
        assertThat(tiny.headers().firstValue("Content-Encoding")).isEmpty();
    }
}
