package com.taskly.entity;

/**
 * 8 cấp Karma như Todoist (khảo sát M10: Beginner 0 → Enlightened 50 000; "Beginner (223) — 277 left to get to Novice"
 * ⇒ Novice = 500). Ngưỡng các cấp giữa theo trang Karma của Todoist [CXM — mở lại trang gốc trước khi phát hành].
 */
public enum KarmaLevel {
    BEGINNER(0),
    NOVICE(500),
    INTERMEDIATE(2_500),
    PROFESSIONAL(5_000),
    EXPERT(7_500),
    MASTER(10_000),
    GRAND_MASTER(20_000),
    ENLIGHTENED(50_000);

    private final long minKarma;

    KarmaLevel(long minKarma) {
        this.minKarma = minKarma;
    }

    public long minKarma() {
        return minKarma;
    }

    /** Cấp cao nhất có minKarma ≤ karma (karma âm coi như 0). */
    public static KarmaLevel of(long karma) {
        KarmaLevel result = BEGINNER;
        for (KarmaLevel level : values()) {
            if (karma >= level.minKarma) {
                result = level;
            }
        }
        return result;
    }

    /** Cấp kế tiếp; null với ENLIGHTENED. */
    public KarmaLevel next() {
        return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null;
    }
}
