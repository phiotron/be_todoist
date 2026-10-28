package com.taskly.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.taskly.config.PushProperties;
import com.taskly.integration.PushSender.PushDeliveryException;
import com.taskly.integration.PushSender.PushMessage;
import com.taskly.integration.PushSender.PushTokenInvalidException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** FcmPushSender với FCM và token endpoint GIẢ (không gọi Firebase thật): đổi JWT lấy access token, gửi, phân loại lỗi. */
class FcmPushSenderTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private KeyPair keyPair;
    private final List<String> tokenRequests = new CopyOnWriteArrayList<>();
    private final List<String> sendBodies = new CopyOnWriteArrayList<>();
    private final List<String> sendAuth = new CopyOnWriteArrayList<>();
    private final AtomicInteger sendStatus = new AtomicInteger(200);
    private volatile String sendResponse = "{}";

    @TempDir
    Path tmp;

    @BeforeEach
    void start() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            tokenRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"access_token\": \"fake-access-token\", \"expires_in\": 3600}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/projects/demo-project/messages:send", exchange -> {
            sendBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sendAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = sendResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(sendStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private FcmPushSender sender(boolean configured) throws Exception {
        String file = "";
        if (configured) {
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                    .encodeToString(keyPair.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            Path account = tmp.resolve("service-account.json");
            Files.writeString(account, json.writeValueAsString(Map.of("client_email", "bot@demo.iam.example",
                    "private_key", pem, "token_uri", base() + "/token")));
            file = account.toString();
        }
        return new FcmPushSender(new PushProperties("fcm", configured ? "demo-project" : "", file, base(), null), json,
                Clock.systemUTC());
    }

    private static PushMessage message() {
        return new PushMessage("device-token-1", "Tiêu đề", "Nội dung", Map.of("taskId", "t-1"));
    }

    @Test
    void sendsWithAnAccessTokenObtainedFromASignedAssertion() throws Exception {
        sender(true).send(message());

        assertThat(tokenRequests).hasSize(1);
        String form = tokenRequests.get(0);
        assertThat(form).contains("grant_type=" + java.net.URLEncoder.encode(
                "urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8));
        String assertion = URLDecoder.decode(form.substring(form.indexOf("assertion=") + "assertion=".length()),
                StandardCharsets.UTF_8);
        SignedJWT jwt = SignedJWT.parse(assertion);
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) keyPair.getPublic()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("bot@demo.iam.example");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("scope")).isEqualTo("https://www.googleapis.com/auth/firebase.messaging");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly(base() + "/token");

        assertThat(sendAuth).containsExactly("Bearer fake-access-token");
        var sent = json.readTree(sendBodies.get(0)).path("message");
        assertThat(sent.path("token").asString("")).isEqualTo("device-token-1");
        assertThat(sent.path("notification").path("title").asString("")).isEqualTo("Tiêu đề");
        assertThat(sent.path("notification").path("body").asString("")).isEqualTo("Nội dung");
        assertThat(sent.path("data").path("taskId").asString("")).isEqualTo("t-1");
        assertThat(sent.path("android").path("priority").asString("")).isEqualTo("HIGH");
    }

    @Test
    void reusesTheAccessTokenWhileItIsValid() throws Exception {
        FcmPushSender sender = sender(true);
        sender.send(message());
        sender.send(message());
        assertThat(tokenRequests).hasSize(1);
        assertThat(sendBodies).hasSize(2);
    }

    @Test
    void unknownTokenIsReportedAsInvalid() throws Exception {
        sendStatus.set(404);
        sendResponse = "{\"error\": {\"status\": \"NOT_FOUND\"}}";
        assertThatThrownBy(() -> sender(true).send(message())).isInstanceOf(PushTokenInvalidException.class);

        sendStatus.set(400);
        sendResponse = "{\"error\": {\"details\": [{\"errorCode\": \"UNREGISTERED\"}]}}";
        assertThatThrownBy(() -> sender(true).send(message())).isInstanceOf(PushTokenInvalidException.class);
    }

    @Test
    void serverErrorIsATransientFailure() {
        sendStatus.set(503);
        assertThatThrownBy(() -> sender(true).send(message())).isInstanceOf(PushDeliveryException.class)
                .isNotInstanceOf(PushTokenInvalidException.class);
    }

    @Test
    void missingConfigurationFailsWithoutCallingTheNetwork() {
        assertThatThrownBy(() -> sender(false).send(message())).isInstanceOf(PushDeliveryException.class)
                .hasMessageContaining("FCM_PROJECT_ID");
        assertThat(tokenRequests).isEmpty();
        assertThat(sendBodies).isEmpty();
    }

    @Test
    void toStringsDoNotLeakTokensOrPaths() {
        assertThat(message().toString()).doesNotContain("device-token-1", "Nội dung");
        assertThat(new PushProperties("fcm", "demo", "/secret/key.json", null, null).toString())
                .doesNotContain("demo", "/secret");
    }

    @Test
    void propertiesDefaults() {
        PushProperties defaults = new PushProperties(null, null, null, null, null);
        assertThat(defaults.provider()).isEqualTo("fake");
        assertThat(defaults.fcmBaseUrl()).isEqualTo("https://fcm.googleapis.com");
        assertThat(new PushProperties("  ", null, null, null, null).provider()).isEqualTo("fake");
    }
}
