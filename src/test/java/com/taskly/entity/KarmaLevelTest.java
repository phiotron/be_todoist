package com.taskly.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 8 cấp Karma (Todoist: Beginner 0 → Enlightened 50 000; "Beginner (223) — 277 left to get to Novice"). */
class KarmaLevelTest {

    @ParameterizedTest
    @CsvSource({
            "0, BEGINNER", "223, BEGINNER", "499, BEGINNER", "500, NOVICE", "2499, NOVICE", "2500, INTERMEDIATE",
            "5000, PROFESSIONAL", "7500, EXPERT", "10000, MASTER", "19999, MASTER", "20000, GRAND_MASTER",
            "50000, ENLIGHTENED", "999999, ENLIGHTENED"})
    void of_picksTheHighestReachedLevel(long karma, KarmaLevel expected) {
        assertThat(KarmaLevel.of(karma)).isEqualTo(expected);
    }

    @Test
    void beginnerWith223_needs277MoreForNovice() {
        KarmaLevel level = KarmaLevel.of(223);
        assertThat(level.next()).isEqualTo(KarmaLevel.NOVICE);
        assertThat(level.next().minKarma() - 223).isEqualTo(277);
    }

    @Test
    void enlightenedHasNoNextLevel() {
        assertThat(KarmaLevel.ENLIGHTENED.next()).isNull();
    }

    @Test
    void eightLevels() {
        assertThat(KarmaLevel.values()).hasSize(8);
    }
}
