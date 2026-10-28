package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;
import com.taskly.config.WebhookProperties;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Luật thuần của M13c: Redirect URI, danh mục sự kiện webhook, chính sách URL webhook, chữ ký HMAC, PKCE. */
class M13cPureRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://app.example/cb", "https://app.example/cb?x=1", "http://localhost:8080/cb",
            "http://127.0.0.1/cb", "http://[::1]:3000/cb", "myapp://oauth/callback", "com.example.app:/cb"})
    void redirectUriAccepted(String uri) {
        assertThat(RedirectUris.validate(uri)).isEqualTo(uri);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "/relative", "http://example.com/cb", "https://", "https://a.example/cb#frag",
            "javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "taskly://reset-password/x",
            "https://user:pw@a.example/cb", "ht tp://bad"})
    void redirectUriRejected(String uri) {
        assertThatThrownBy(() -> RedirectUris.validate(uri)).isInstanceOf(ApiException.class);
    }

    @Test
    void redirectUriTooLongRejected() {
        assertThatThrownBy(() -> RedirectUris.validate("https://a.example/" + "x".repeat(2048)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RedirectUris.validate(null)).isInstanceOf(ApiException.class);
    }

    @Test
    void webhookEventsResolveFromRoute() {
        assertThat(WebhookEvents.resolve("POST", "/api/v1/tasks", 201)).contains("item:added");
        assertThat(WebhookEvents.resolve("POST", "/api/v1/tasks", 200)).isEmpty(); // phát lại theo id client
        assertThat(WebhookEvents.resolve("PATCH", "/api/v1/tasks/{taskId}", 200)).contains("item:updated");
        assertThat(WebhookEvents.resolve("DELETE", "/api/v1/tasks/{taskId}", 204)).contains("item:deleted");
        assertThat(WebhookEvents.resolve("POST", "/api/v1/tasks/{taskId}/close", 200)).contains("item:completed");
        assertThat(WebhookEvents.resolve("POST", "/api/v1/tasks/{taskId}/reopen", 200)).contains("item:uncompleted");
        assertThat(WebhookEvents.resolve("POST", "/api/v1/projects/{projectId}/archive", 200))
                .contains("project:archived");
        assertThat(WebhookEvents.resolve("POST", "/api/v1/projects/{projectId}/sections", 201))
                .contains("section:added");
        assertThat(WebhookEvents.resolve("DELETE", "/api/v1/projects/{projectId}/sections/{sectionId}", 204))
                .contains("section:deleted");
        assertThat(WebhookEvents.resolve("PATCH", "/api/v1/labels/{labelId}", 200)).contains("label:updated");
        assertThat(WebhookEvents.resolve("POST", "/api/v1/filters", 201)).contains("filter:added");
    }

    @Test
    void webhookEventsIgnoreUnrelatedRoutesAndFailures() {
        assertThat(WebhookEvents.resolve("GET", "/api/v1/tasks", 200)).isEmpty();
        assertThat(WebhookEvents.resolve("POST", "/api/v1/tasks/batch", 200)).isEmpty();
        assertThat(WebhookEvents.resolve("PATCH", "/api/v1/tasks/{taskId}", 409)).isEmpty();
        assertThat(WebhookEvents.resolve("PATCH", "/api/v1/me/settings", 200)).isEmpty();
        assertThat(WebhookEvents.resolve("POST", null, 200)).isEmpty();
        assertThat(WebhookEvents.ALL).hasSize(21);
    }

    @Test
    void webhookUrlPolicyRequiresHttpsUnlessInsecureAllowed() {
        WebhookUrlPolicy strict = new WebhookUrlPolicy(new WebhookProperties(false, null, null));
        assertThat(strict.validate("https://hooks.example/in")).isEqualTo("https://hooks.example/in");
        assertThatThrownBy(() -> strict.validate("http://hooks.example/in")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> strict.validate("https://u:p@hooks.example/in")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> strict.validate("ftp://hooks.example")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> strict.validate("")).isInstanceOf(ApiException.class);
        WebhookUrlPolicy relaxed = new WebhookUrlPolicy(new WebhookProperties(true, null, null));
        assertThat(relaxed.validate("http://localhost:9000/x")).isEqualTo("http://localhost:9000/x");
    }

    @Test
    void webhookUrlPolicyBlocksInternalAddressesWhenStrict() throws Exception {
        WebhookUrlPolicy strict = new WebhookUrlPolicy(new WebhookProperties(false, null, null));
        assertThat(strict.isSafeToCall("https://127.0.0.1/x")).isFalse();
        assertThat(strict.isSafeToCall("https://10.0.0.5/x")).isFalse();
        assertThat(strict.isSafeToCall("https://192.168.1.2/x")).isFalse();
        assertThat(strict.isSafeToCall("https://169.254.169.254/latest")).isFalse();
        assertThat(strict.isSafeToCall("https://100.64.1.1/x")).isFalse();
        assertThat(strict.isSafeToCall("https://[::1]/x")).isFalse();
        assertThat(strict.isSafeToCall("https://[fd00::1]/x")).isFalse();
        assertThat(strict.isSafeToCall("https://8.8.8.8/x")).isTrue();
        assertThat(WebhookUrlPolicy.isInternal(InetAddress.getByName("172.16.0.1"))).isTrue();
        assertThat(new WebhookUrlPolicy(new WebhookProperties(true, null, null)).isSafeToCall("http://127.0.0.1/x"))
                .isTrue();
    }

    @Test
    void hmacSignatureMatchesIndependentComputation() throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = java.util.Base64.getEncoder().encodeToString(
                mac.doFinal("{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(WebhookDispatchService.sign("secret", "{\"a\":1}")).isEqualTo(expected);
        assertThat(WebhookDispatchService.sign("secret2", "{\"a\":1}")).isNotEqualTo(expected);
        assertThat(WebhookDispatchService.sign("secret", "{\"a\":2}")).isNotEqualTo(expected);
    }

    @Test
    void pkceS256MatchesRfc7636Example() {
        // RFC 7636 phụ lục B
        assertThat(OAuthService.pkceMatches("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")).isTrue();
        assertThat(OAuthService.pkceMatches("wrong-verifier-wrong-verifier-wrong-verifier-123",
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")).isFalse();
    }
}
