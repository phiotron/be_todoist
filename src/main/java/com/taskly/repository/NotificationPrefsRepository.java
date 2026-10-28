package com.taskly.repository;

import com.taskly.dto.notification.NotificationPrefsResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Cài đặt thông báo từ server (bảng notification_prefs). Chưa có hàng = mặc định (xem NotificationPrefsService). */
@Repository
public class NotificationPrefsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationPrefsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<NotificationPrefsResponse> find(UUID userId) {
        return jdbc.query("SELECT * FROM notification_prefs WHERE user_id = :user",
                new MapSqlParameterSource("user", userId),
                (rs, n) -> new NotificationPrefsResponse(rs.getBoolean("reminder_push"),
                        rs.getBoolean("reminder_email"), rs.getBoolean("morning_overview"),
                        rs.getBoolean("evening_review"), rs.getBoolean("subscribed_emails"))).stream().findFirst();
    }

    public void upsert(UUID userId, NotificationPrefsResponse prefs, Instant now) {
        jdbc.update("""
                INSERT INTO notification_prefs (user_id, reminder_push, reminder_email, morning_overview,
                    evening_review, subscribed_emails, created_at, updated_at)
                VALUES (:user, :push, :email, :morning, :evening, :subscribed, :now, :now)
                ON CONFLICT (user_id) DO UPDATE SET reminder_push = EXCLUDED.reminder_push,
                    reminder_email = EXCLUDED.reminder_email, morning_overview = EXCLUDED.morning_overview,
                    evening_review = EXCLUDED.evening_review, subscribed_emails = EXCLUDED.subscribed_emails,
                    updated_at = EXCLUDED.updated_at
                """, new MapSqlParameterSource().addValue("user", userId).addValue("push", prefs.reminderPush())
                .addValue("email", prefs.reminderEmail()).addValue("morning", prefs.morningOverview())
                .addValue("evening", prefs.eveningReview()).addValue("subscribed", prefs.subscribedEmails())
                .addValue("now", SqlTime.ts(now)));
    }

    /** Người dùng bật nhắc qua push hoặc email (job nhắc việc quét theo danh sách này). */
    public List<UUID> usersWithReminders() {
        return jdbc.query("SELECT user_id FROM notification_prefs WHERE reminder_push OR reminder_email",
                (rs, n) -> rs.getObject("user_id", UUID.class));
    }

    /** Người dùng bật Morning overview hoặc Evening review, kèm thông tin để soạn thư. */
    public List<DigestUser> usersWithDigests() {
        return jdbc.query("""
                SELECT u.id, u.email, u.display_name, u.locale, u.time_zone, p.morning_overview, p.evening_review
                FROM notification_prefs p JOIN users u ON u.id = p.user_id
                WHERE p.morning_overview OR p.evening_review
                """, (rs, n) -> new DigestUser(rs.getObject("id", UUID.class), rs.getString("email"),
                rs.getString("display_name"), rs.getString("locale"), rs.getString("time_zone"),
                rs.getBoolean("morning_overview"), rs.getBoolean("evening_review")));
    }

    public record DigestUser(UUID id, String email, String displayName, String locale, String timeZone,
                             boolean morning, boolean evening) {
    }
}
