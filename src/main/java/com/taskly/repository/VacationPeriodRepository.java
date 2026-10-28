package com.taskly.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Kỳ nghỉ (Vacation mode, M10 — V14 vacation_periods). JDBC thuần, chạy trong transaction của service gọi. */
@Repository
public class VacationPeriodRepository {

    /** Một kỳ nghỉ; endedOn null = đang nghỉ. */
    public record Period(LocalDate startedOn, LocalDate endedOn) {

        /** day nằm trong kỳ (hai đầu tính cả); kỳ đang mở kéo tới today. */
        public boolean contains(LocalDate day, LocalDate today) {
            LocalDate end = endedOn == null ? today : endedOn;
            return !day.isBefore(startedOn) && !day.isAfter(end);
        }
    }

    private final JdbcTemplate jdbc;

    public VacationPeriodRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Mở kỳ mới nếu chưa có kỳ đang mở (chỉ mục uq_vacation_periods_open chặn kỳ thứ hai). */
    public void open(UUID userId, LocalDate today, Instant now) {
        jdbc.update("insert into vacation_periods (id, user_id, started_on, ended_on, created_at) "
                        + "values (?, ?, ?, null, ?) on conflict do nothing",
                UUID.randomUUID(), userId, today, Timestamp.from(now));
    }

    /** Đóng kỳ đang mở tại today (kỳ mở và đóng cùng ngày vẫn giữ ngày đó). */
    public void close(UUID userId, LocalDate today) {
        jdbc.update("update vacation_periods set ended_on = greatest(started_on, ?) "
                + "where user_id = ? and ended_on is null", today, userId);
    }

    public List<Period> findByUser(UUID userId) {
        return jdbc.query("select started_on, ended_on from vacation_periods where user_id = ? order by started_on",
                (rs, i) -> new Period(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)), userId);
    }
}
