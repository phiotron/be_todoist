package com.taskly.service.filterquery;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Ngày trong filter, chưa gắn "hôm nay": quy ra LocalDate lúc dịch sang SQL (theo múi giờ tài khoản). */
public sealed interface FilterDate {

    LocalDate resolve(LocalDate today);

    String describe();

    /** Có kèm giờ (yyyy-MM-ddTHH:mm[:ss]) — chỉ AbsoluteTime; deadline không nhận. */
    default boolean hasTime() {
        return false;
    }

    /** today (0), tomorrow (+1), yesterday (-1). */
    record Relative(int offsetDays) implements FilterDate {
        @Override
        public LocalDate resolve(LocalDate today) {
            return today.plusDays(offsetDays);
        }

        @Override
        public String describe() {
            if (offsetDays == 0) {
                return "today";
            }
            return offsetDays > 0 ? "today+" + offsetDays : "today-" + (-offsetDays);
        }
    }

    /** yyyy-MM-dd hoặc dd/MM/yyyy. */
    record Absolute(LocalDate date) implements FilterDate {
        @Override
        public LocalDate resolve(LocalDate today) {
            return date;
        }

        @Override
        public String describe() {
            return date.toString();
        }
    }

    /** dd/MM không năm → năm hiện tại; 29/02 ở năm không nhuận → 28/02 (MonthDay.atYear). */
    record DayMonth(MonthDay monthDay) implements FilterDate {
        @Override
        public LocalDate resolve(LocalDate today) {
            return monthDay.atYear(today.getYear());
        }

        @Override
        public String describe() {
            return monthDay.toString();
        }
    }

    /** yyyy-MM-ddTHH:mm[:ss]: ngày giờ floating (spec 5.2) — không múi giờ; giây cắt về phút, xuất luôn "…:ss" = 00. */
    record AbsoluteTime(LocalDateTime dateTime) implements FilterDate {
        private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

        public AbsoluteTime {
            dateTime = dateTime.truncatedTo(ChronoUnit.MINUTES);
        }

        @Override
        public LocalDate resolve(LocalDate today) {
            return dateTime.toLocalDate();
        }

        @Override
        public boolean hasTime() {
            return true;
        }

        @Override
        public String describe() {
            return FORMAT.format(dateTime);
        }
    }
}
