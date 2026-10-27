package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import com.taskly.security.OpaqueTokens;
import com.taskly.security.RecoveryCodeHasher;
import com.taskly.security.RecoveryCodes;
import com.taskly.support.TwoFactorSteps;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** me/2fa/* (spec 12.1): setup, enable (10 mã khôi phục), disable, tạo lại mã khôi phục, mã dùng một lần. */
class TwoFactorApiTest extends IntegrationTestBase {

    private static final String BASE = "/api/v1/me/2fa";

    @Autowired
    private RecoveryCodeHasher recoveryCodeHasher;

    private ResultActions status2fa(String accessToken) throws Exception {
        return mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));
    }

    private ResultActions postJson(String path, String accessToken, String json) throws Exception {
        return mockMvc.perform(post(BASE + path).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions enable(String accessToken, String code) throws Exception {
        return postJson("/enable", accessToken, "{\"code\": \"" + code + "\"}");
    }

    private ResultActions disable(String accessToken, String password, String code) throws Exception {
        String passwordJson = password == null ? "" : "\"password\": \"" + password + "\", ";
        return postJson("/disable", accessToken, "{" + passwordJson + "\"code\": \"" + code + "\"}");
    }

    private int countRows(String table, UUID userId) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where user_id = ?", Integer.class, userId);
    }

    @Test
    void status_newUser_isDisabledWithNoRecoveryCodes() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        status2fa(user.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.recoveryCodesLeft").value(0));
    }

    @Test
    void setup_returnsBase32SecretAndOtpauthUri_andStoresOnlyEncryptedSecret() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        String json = body(mockMvc.perform(post(BASE + "/setup")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andReturn());
        String secret = JsonPath.read(json, "$.secret");
        String uri = JsonPath.read(json, "$.otpauthUri");

        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(uri).startsWith("otpauth://totp/Taskly:").contains("secret=" + secret).contains("issuer=Taskly")
                .contains("digits=6").contains("period=30");
        String stored = jdbcTemplate.queryForObject(
                "select secret_encrypted from user_totp where user_id = ? and enabled_at is null", String.class,
                user.id());
        assertThat(stored).isNotBlank().doesNotContain(secret);
        status2fa(user.accessToken()).andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void setupAgainBeforeEnabling_replacesThePendingSecret() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String first = TwoFactorSteps.setup(mockMvc, user.accessToken());
        String second = TwoFactorSteps.setup(mockMvc, user.accessToken());

        assertThat(second).isNotEqualTo(first);
        assertThat(countRows("user_totp", user.id())).isEqualTo(1);
        enable(user.accessToken(), TwoFactorSteps.code(first, clock)).andExpect(status().isBadRequest());
        enable(user.accessToken(), TwoFactorSteps.code(second, clock)).andExpect(status().isOk());
    }

    @Test
    void enable_withoutSetup_returns409NotSetup() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        enable(user.accessToken(), "123456")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("two-factor-not-setup"))
                .andExpect(jsonPath("$.detail").value("Chưa thiết lập xác minh hai bước, hãy tạo mã bí mật trước"));
    }

    @Test
    void enable_wrongCode_returns400_andStaysDisabled() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String secret = TwoFactorSteps.setup(mockMvc, user.accessToken());
        String wrong = TwoFactorSteps.wrongCode(secret, clock);

        enable(user.accessToken(), wrong)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Mã xác minh không đúng"))
                .andExpect(jsonPath("$.code").doesNotExist());
        status2fa(user.accessToken()).andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void enable_validCode_returnsTenRecoveryCodesOnce_andStoresOnlyHashes() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());

        assertThat(enabled.recoveryCodes()).hasSize(10).doesNotHaveDuplicates()
                .allMatch(code -> code.matches("[a-z2-7]{4}-[a-z2-7]{4}"));
        status2fa(user.accessToken())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.recoveryCodesLeft").value(10));
        List<String> hashes = jdbcTemplate.queryForList(
                "select code_hash from user_recovery_codes where user_id = ?", String.class, user.id());
        String normalized = RecoveryCodes.normalize(enabled.recoveryCodes().get(0)).orElseThrow();
        String firstHash = recoveryCodeHasher.hash(user.id(), normalized);
        assertThat(hashes).hasSize(10).contains(firstHash).doesNotContainAnyElementsOf(enabled.recoveryCodes())
                .doesNotContain(OpaqueTokens.sha256Hex(normalized));
    }

    @Test
    void setupOrEnable_whenAlreadyEnabled_returns409AlreadyEnabled() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());

        mockMvc.perform(post(BASE + "/setup").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("two-factor-already-enabled"));
        enable(user.accessToken(), TwoFactorSteps.nextCode(enabled.secret(), clock))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("two-factor-already-enabled"));
    }

    @Test
    void disable_needsCurrentPasswordAndValidCode_thenRemovesEverything() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());
        String code = TwoFactorSteps.nextCode(enabled.secret(), clock);

        disable(user.accessToken(), "Wrong-Pass-999", code)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Mật khẩu hiện tại không đúng"));
        disable(user.accessToken(), null, code)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Mật khẩu hiện tại không đúng"));
        disable(user.accessToken(), FIXTURE_PASSWORD, TwoFactorSteps.wrongCode(enabled.secret(), clock))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Mã xác minh không đúng"));
        disable(user.accessToken(), FIXTURE_PASSWORD, code)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        status2fa(user.accessToken())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.recoveryCodesLeft").value(0));
        assertThat(countRows("user_totp", user.id())).isZero();
        assertThat(countRows("user_recovery_codes", user.id())).isZero();
    }

    @Test
    void disable_acceptsARecoveryCode() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());

        disable(user.accessToken(), FIXTURE_PASSWORD, enabled.recoveryCodes().get(3).toUpperCase())
                .andExpect(status().isNoContent());
        status2fa(user.accessToken()).andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void disableOrRegenerate_whenNotEnabled_returns409NotSetup() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.setup(mockMvc, user.accessToken()); // đã setup nhưng chưa bật

        disable(user.accessToken(), FIXTURE_PASSWORD, "123456")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("two-factor-not-setup"))
                .andExpect(jsonPath("$.detail").value("Xác minh hai bước chưa được bật"));
        postJson("/recovery-codes", user.accessToken(), "{\"code\": \"123456\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("two-factor-not-setup"));
    }

    @Test
    void regenerateRecoveryCodes_replacesOldOnes() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());

        String json = body(postJson("/recovery-codes", user.accessToken(),
                        "{\"code\": \"" + TwoFactorSteps.nextCode(enabled.secret(), clock) + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes", hasSize(10)))
                .andReturn());
        List<String> fresh = JsonPath.read(json, "$.recoveryCodes");

        assertThat(fresh).doesNotContainAnyElementsOf(enabled.recoveryCodes());
        disable(user.accessToken(), FIXTURE_PASSWORD, enabled.recoveryCodes().get(0))
                .andExpect(status().isBadRequest());
        disable(user.accessToken(), FIXTURE_PASSWORD, fresh.get(0)).andExpect(status().isNoContent());
    }

    @Test
    void totpCode_cannotBeReused_nextStepWorks() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String secret = TwoFactorSteps.setup(mockMvc, user.accessToken());
        String used = TwoFactorSteps.code(secret, clock);
        enable(user.accessToken(), used).andExpect(status().isOk());

        postJson("/recovery-codes", user.accessToken(), "{\"code\": \"" + used + "\"}")
                .andExpect(status().isBadRequest());
        postJson("/recovery-codes", user.accessToken(),
                        "{\"code\": \"" + TwoFactorSteps.nextCode(secret, clock) + "\"}")
                .andExpect(status().isOk());
    }

    @Test
    void totpCode_withSpaceInTheMiddle_isAccepted() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String secret = TwoFactorSteps.setup(mockMvc, user.accessToken());
        String code = TwoFactorSteps.code(secret, clock);

        enable(user.accessToken(), code.substring(0, 3) + " " + code.substring(3)).andExpect(status().isOk());
    }

    @Test
    void blankCode_returns400WithFieldError() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.setup(mockMvc, user.accessToken());

        enable(user.accessToken(), "  ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'code')].message", contains("Vui lòng nhập mã xác minh")));
    }

    @Test
    void conflict_isTranslatedToEnglish_andKeepsCode() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(post(BASE + "/enable").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"123456\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail")
                        .value("Two-step verification has not been set up yet, please create a secret first"))
                .andExpect(jsonPath("$.code").value("two-factor-not-setup"));
    }

    @Test
    void endpoints_withoutToken_return401() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/setup")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/enable").contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"1\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/disable").contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"1\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/recovery-codes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void secretAndCodes_neverAppearInStatus() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        TwoFactorSteps.Enabled enabled = TwoFactorSteps.enable(mockMvc, clock, user.accessToken());

        String json = body(status2fa(user.accessToken()).andReturn());
        assertThat(json).doesNotContain(enabled.secret()).doesNotContain(enabled.recoveryCodes().get(0))
                .doesNotContain(OpaqueTokens.sha256Hex(RecoveryCodes.normalize(enabled.recoveryCodes().get(0))
                        .orElseThrow()));
    }
}
