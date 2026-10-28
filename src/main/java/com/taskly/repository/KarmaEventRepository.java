package com.taskly.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bảng karma_events (M10, V14). JDBC thuần trong transaction của service gọi. Số hoàn thành của ngày D = số dòng
 * TASK_COMPLETED có occurred_on = D trừ số dòng TASK_UNCOMPLETED có occurred_on = D (lần huỷ mang ngày của lần cộng).
 */
@Repository
public class KarmaEventRepository {

    public static final String TASK_COMPLETED = "TASK_COMPLETED";
    public static final String TASK_UNCOMPLETED = "TASK_UNCOMPLETED";
    public static final String DAILY_GOAL = "DAILY_GOAL";
    public static final String WEEKLY_GOAL = "WEEKLY_GOAL";
    public static final String OVERDUE_PENALTY = "OVERDUE_PENALTY";

    private final NamedParameterJdbcTemplate jdbc;

    public KarmaEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Một lần cộng TASK_COMPLETED cho mỗi task. */
    public void insertCompletions(UUID userId, Collection<UUID> taskIds, int points, LocalDate day, Instant now) {
        MapSqlParameterSource[] batch = taskIds.stream()
                .map(taskId -> new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("userId", userId)
                        .addValue("points", points)
                        .addValue("taskId", taskId)
                        .addValue("day", day)
                        .addValue("now", Timestamp.from(now)))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("insert into karma_events (id, user_id, reason, points, task_id, occurred_on, created_at) "
                + "values (:id, :userId, 'TASK_COMPLETED', :points, :taskId, :day, :now)", batch);
    }

    /**
     * Huỷ lần cộng GẦN NHẤT chưa bị huỷ của từng task (mở lại / Undo): dòng TASK_UNCOMPLETED cho đúng người được cộng, điểm
     * âm bằng điểm đã cộng, cùng ngày với lần cộng. Task chưa từng được cộng (hoàn thành trước M10) → không ghi.
     */
    public void reverseCompletions(Collection<UUID> taskIds, Instant now) {
        if (taskIds.isEmpty()) {
            return;
        }
        jdbc.update("""
                insert into karma_events (id, user_id, reason, points, task_id, occurred_on, ref_date, created_at)
                select gen_random_uuid(), c.user_id, 'TASK_UNCOMPLETED', -c.points, c.task_id, c.occurred_on,
                       c.occurred_on, :now
                from (select distinct on (task_id) user_id, points, task_id, occurred_on
                      from karma_events
                      where task_id in (:ids) and reason = 'TASK_COMPLETED'
                      order by task_id, created_at desc, id desc) c
                where (select count(*) from karma_events k where k.task_id = c.task_id and k.reason = 'TASK_COMPLETED')
                    > (select count(*) from karma_events k where k.task_id = c.task_id and k.reason = 'TASK_UNCOMPLETED')
                """, new MapSqlParameterSource().addValue("ids", taskIds).addValue("now", Timestamp.from(now)));
    }

    /** Thưởng mục tiêu (DAILY_GOAL / WEEKLY_GOAL) một lần cho mỗi refDate (chỉ mục uq_karma_events_goal). */
    public void insertGoalBonus(UUID userId, String reason, int points, LocalDate day, LocalDate refDate, Instant now) {
        jdbc.update("insert into karma_events (id, user_id, reason, points, occurred_on, ref_date, created_at) "
                        + "values (:id, :userId, :reason, :points, :day, :refDate, :now) on conflict do nothing",
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("userId", userId)
                        .addValue("reason", reason)
                        .addValue("points", points)
                        .addValue("day", day)
                        .addValue("refDate", refDate)
                        .addValue("now", Timestamp.from(now)));
    }

    /**
     * Phạt mỗi task ĐANG MỞ do user tạo, trong dự án chưa lưu trữ, có due_day + overdueDays ≤ today: một lần cho mỗi
     * (task, due_day), tính vào ngày due_day + overdueDays; bỏ qua nếu ngày đó nằm trong kỳ nghỉ.
     */
    public void insertOverduePenalties(UUID userId, int points, int overdueDays, LocalDate today, Instant now) {
        jdbc.update("""
                insert into karma_events (id, user_id, reason, points, task_id, occurred_on, ref_date, created_at)
                select gen_random_uuid(), :userId, 'OVERDUE_PENALTY', :points, t.id, t.due_day + :days, t.due_day, :now
                from tasks t
                join projects p on p.id = t.project_id
                where t.creator_id = :userId
                  and t.completed_at is null
                  and p.archived_at is null
                  and t.due_day is not null
                  and t.due_day + :days <= :today
                  and not exists (select 1 from vacation_periods v
                                  where v.user_id = :userId
                                    and v.started_on <= t.due_day + :days
                                    and coalesce(v.ended_on, :today) >= t.due_day + :days)
                on conflict do nothing
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("points", points)
                .addValue("days", overdueDays)
                .addValue("today", today)
                .addValue("now", Timestamp.from(now)));
    }

    /** Số hoàn thành của một ngày. */
    public int completedOn(UUID userId, LocalDate day) {
        return completedBetween(userId, day, day);
    }

    /** Số hoàn thành từ from tới to (hai đầu tính cả). */
    public int completedBetween(UUID userId, LocalDate from, LocalDate to) {
        Integer count = jdbc.queryForObject("""
                select coalesce(sum(case reason when 'TASK_COMPLETED' then 1 else -1 end), 0)
                from karma_events
                where user_id = :userId and reason in ('TASK_COMPLETED', 'TASK_UNCOMPLETED')
                  and occurred_on between :from and :to
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("from", from).addValue("to", to),
                Integer.class);
        return count == null ? 0 : count;
    }

    /** Số hoàn thành theo ngày (chỉ ngày khác 0), toàn bộ lịch sử. */
    public Map<LocalDate, Integer> completionsByDay(UUID userId) {
        Map<LocalDate, Integer> result = new LinkedHashMap<>();
        jdbc.query("""
                select occurred_on, sum(case reason when 'TASK_COMPLETED' then 1 else -1 end) as n
                from karma_events
                where user_id = :userId and reason in ('TASK_COMPLETED', 'TASK_UNCOMPLETED')
                group by occurred_on
                having sum(case reason when 'TASK_COMPLETED' then 1 else -1 end) <> 0
                order by occurred_on
                """, new MapSqlParameterSource("userId", userId),
                rs -> {
                    result.put(rs.getObject("occurred_on", LocalDate.class), rs.getInt("n"));
                });
        return result;
    }

    /** Tổng điểm có occurred_on < before. */
    public long sumBefore(UUID userId, LocalDate before) {
        Long sum = jdbc.queryForObject("select coalesce(sum(points), 0) from karma_events "
                        + "where user_id = :userId and occurred_on < :before",
                new MapSqlParameterSource().addValue("userId", userId).addValue("before", before), Long.class);
        return sum == null ? 0 : sum;
    }

    /** Tổng điểm theo ngày trong [from, to]. */
    public Map<LocalDate, Long> pointsByDay(UUID userId, LocalDate from, LocalDate to) {
        Map<LocalDate, Long> result = new LinkedHashMap<>();
        jdbc.query("select occurred_on, sum(points) as p from karma_events where user_id = :userId "
                        + "and occurred_on between :from and :to group by occurred_on",
                new MapSqlParameterSource().addValue("userId", userId).addValue("from", from).addValue("to", to),
                rs -> {
                    result.put(rs.getObject("occurred_on", LocalDate.class), rs.getLong("p"));
                });
        return result;
    }

    /** Tổng mọi điểm (có thể âm — hiển thị chặn ở 0). */
    public long total(UUID userId) {
        Long sum = jdbc.queryForObject("select coalesce(sum(points), 0) from karma_events where user_id = :userId",
                new MapSqlParameterSource("userId", userId), Long.class);
        return sum == null ? 0 : sum;
    }
}
