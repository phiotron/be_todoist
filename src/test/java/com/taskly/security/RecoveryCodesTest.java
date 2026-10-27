package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit thuần: 10 mã khôi phục dùng một lần, chuẩn hoá khi người dùng gõ lại, chỉ lưu SHA-256. */
class RecoveryCodesTest {

    @Test
    void generate_returnsTenDistinctCodesInXxxxDashXxxxFormat() {
        List<String> codes = RecoveryCodes.generate();

        assertThat(codes).hasSize(RecoveryCodes.COUNT).hasSize(10).doesNotHaveDuplicates()
                .allMatch(code -> code.matches("[a-z2-7]{4}-[a-z2-7]{4}"));
        assertThat(RecoveryCodes.generate()).doesNotContainAnyElementsOf(codes);
    }

    @Test
    void normalize_acceptsUpperCaseSpacesAndMissingDash() {
        assertThat(RecoveryCodes.normalize("abcd-ef23")).contains("abcdef23");
        assertThat(RecoveryCodes.normalize("  ABCD-EF23 ")).contains("abcdef23");
        assertThat(RecoveryCodes.normalize("abcdef23")).contains("abcdef23");
        assertThat(RecoveryCodes.normalize("abcd ef23")).contains("abcdef23");
    }

    @Test
    void normalize_rejectsWrongLengthCharactersOrNull() {
        assertThat(RecoveryCodes.normalize(null)).isEmpty();
        assertThat(RecoveryCodes.normalize("")).isEmpty();
        assertThat(RecoveryCodes.normalize("abcd-ef2")).isEmpty();
        assertThat(RecoveryCodes.normalize("abcd-ef234")).isEmpty();
        assertThat(RecoveryCodes.normalize("abcd-ef01")).as("0 và 1 không thuộc bảng chữ").isEmpty();
        assertThat(RecoveryCodes.normalize("123456")).as("mã TOTP không phải mã khôi phục").isEmpty();
    }

    @Test
    void hash_isSha256HexOfNormalizedCode() {
        String hash = RecoveryCodes.hash("abcdef23");

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}").isEqualTo(OpaqueTokens.sha256Hex("abcdef23"));
    }
}
