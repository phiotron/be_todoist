package com.taskly.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.common.validation.SettingsTargets.PinnedKind;
import com.taskly.common.validation.SettingsTargets.PinnedTarget;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit thuần: định dạng startPage / navTabs của me/settings (spec mục 10, 12.1.1). */
class SettingsTargetsTest {

    private static final String ID = "3f2b8c1e-7d4a-4b6e-9c1d-2a5e8f0b7c3d";
    private static final String UPPER_ID = "3F2B8C1E-7D4A-4B6E-9C1D-2A5E8F0B7C3D";
    private static final String OTHER_ID = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

    @ParameterizedTest
    @ValueSource(strings = {"INBOX", "TODAY", "UPCOMING", "project:" + ID, "label:" + ID, "filter:" + ID})
    void startPage_acceptsFixedPagesAndPinnedTargets(String value) {
        assertThat(SettingsTargets.isValidStartPage(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "today", "SEARCH", "BROWSE", "FILTERS_LABELS", "project:", "project:not-a-uuid",
            "PROJECT:" + ID, "project:" + UPPER_ID, "task:" + ID, " TODAY"})
    void startPage_rejectsUnknownMiscasedOrMalformedValues(String value) {
        assertThat(SettingsTargets.isValidStartPage(value)).isFalse();
    }

    @Test
    void pinned_parsesKindAndId() {
        assertThat(SettingsTargets.pinned("project:" + ID))
                .contains(new PinnedTarget(PinnedKind.PROJECT, UUID.fromString(ID)));
        assertThat(SettingsTargets.pinned("label:" + ID).orElseThrow().kind()).isEqualTo(PinnedKind.LABEL);
        assertThat(SettingsTargets.pinned("filter:" + ID).orElseThrow().value()).isEqualTo("filter:" + ID);
    }

    @Test
    void pinned_isEmptyForFixedKeysMalformedValuesAndNull() {
        assertThat(SettingsTargets.pinned("TODAY")).isEmpty();
        assertThat(SettingsTargets.pinned("project:" + UPPER_ID)).isEmpty();
        assertThat(SettingsTargets.pinned(null)).isEmpty();
    }

    @Test
    void navTabs_acceptsThreeToFiveDistinctTabsEndingWithBrowseAndAtMostOnePin() {
        assertThat(SettingsTargets.isValidNavTabs(SettingsTargets.DEFAULT_NAV_TABS)).isTrue();
        assertThat(SettingsTargets.isValidNavTabs(List.of("TODAY", "INBOX", "BROWSE"))).isTrue();
        assertThat(SettingsTargets.isValidNavTabs(List.of("INBOX", "TODAY", "UPCOMING", "SEARCH", "BROWSE"))).isTrue();
        assertThat(SettingsTargets.isValidNavTabs(List.of("TODAY", "FILTERS_LABELS", "BROWSE"))).isTrue();
        assertThat(SettingsTargets.isValidNavTabs(List.of("TODAY", "project:" + ID, "BROWSE"))).isTrue();
        assertThat(SettingsTargets.isValidNavTabs(List.of("filter:" + ID, "SEARCH", "INBOX", "TODAY", "BROWSE")))
                .isTrue();
    }

    @Test
    void navTabs_rejectsWrongSizeOrderDuplicatesPinsOrUnknownKeys() {
        assertThat(SettingsTargets.isValidNavTabs(List.of("INBOX", "BROWSE"))).as("2 tab").isFalse();
        assertThat(SettingsTargets.isValidNavTabs(
                List.of("INBOX", "TODAY", "UPCOMING", "SEARCH", "FILTERS_LABELS", "BROWSE"))).as("6 tab").isFalse();
        assertThat(SettingsTargets.isValidNavTabs(List.of("BROWSE", "INBOX", "TODAY"))).as("BROWSE không ở cuối")
                .isFalse();
        assertThat(SettingsTargets.isValidNavTabs(List.of("INBOX", "TODAY", "UPCOMING"))).as("thiếu BROWSE").isFalse();
        assertThat(SettingsTargets.isValidNavTabs(List.of("INBOX", "INBOX", "BROWSE"))).as("trùng").isFalse();
        assertThat(SettingsTargets.isValidNavTabs(List.of("project:" + ID, "label:" + OTHER_ID, "BROWSE")))
                .as("hai mục ghim").isFalse();
        assertThat(SettingsTargets.isValidNavTabs(List.of("HOME", "TODAY", "BROWSE"))).as("khoá lạ").isFalse();
        assertThat(SettingsTargets.isValidNavTabs(List.of("TODAY", "TEAM", "BROWSE")))
                .as("TEAM chỉ hợp lệ khi M8 đã gộp (spec 12.1.1) — M8 thêm vào danh sách khoá").isFalse();
        assertThat(SettingsTargets.isValidNavTabs(List.of("inbox", "TODAY", "BROWSE"))).as("chữ thường").isFalse();
        assertThat(SettingsTargets.isValidNavTabs(Arrays.asList("INBOX", null, "BROWSE"))).as("phần tử null")
                .isFalse();
        assertThat(SettingsTargets.isValidNavTabs(List.of())).as("rỗng").isFalse();
        assertThat(SettingsTargets.isValidNavTabs(null)).as("null").isFalse();
    }

    @Test
    void pinnedIn_collectsPinnedTargetsAndIgnoresFixedKeysAndNull() {
        List<PinnedTarget> pinned = SettingsTargets.pinnedIn(
                Arrays.asList("TODAY", "project:" + ID, null, "label:" + OTHER_ID, "BROWSE"));

        assertThat(pinned).containsExactly(
                new PinnedTarget(PinnedKind.PROJECT, UUID.fromString(ID)),
                new PinnedTarget(PinnedKind.LABEL, UUID.fromString(OTHER_ID)));
    }

    @Test
    void validators_treatNullAsValid_andDelegateToSettingsTargets() {
        StartPageValidator startPage = new StartPageValidator();
        NavTabsValidator navTabs = new NavTabsValidator();

        assertThat(startPage.isValid(null, null)).isTrue();
        assertThat(startPage.isValid("UPCOMING", null)).isTrue();
        assertThat(startPage.isValid("SEARCH", null)).isFalse();
        assertThat(navTabs.isValid(null, null)).isTrue();
        assertThat(navTabs.isValid(List.of("TODAY", "INBOX", "BROWSE"), null)).isTrue();
        assertThat(navTabs.isValid(List.of("TODAY", "BROWSE"), null)).isFalse();
    }
}
