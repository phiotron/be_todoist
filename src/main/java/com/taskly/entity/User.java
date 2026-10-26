package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Người dùng + cài đặt theo tài khoản (giao diện, ngôn ngữ, múi giờ). password_hash KHÔNG bao giờ rời tầng service
 * (Controller chỉ thấy UserResponse / SettingsResponse).
 * Các hàm change* gán thẳng giá trị: gán giá trị giống hệt thì Hibernate không thấy "dirty" → không UPDATE →
 * updated_at giữ nguyên (BR-10, spec mục 5.6).
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    public static final String DEFAULT_LOCALE = "vi";
    public static final String DEFAULT_TIME_ZONE = "Asia/Ho_Chi_Minh";
    public static final Theme DEFAULT_THEME = Theme.SYSTEM;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Enumerated(EnumType.STRING)
    @Column(name = "theme", nullable = false, length = 10)
    private Theme theme;

    protected User() {
        // cho JPA
    }

    /** email phải đã được chuẩn hoá bằng EmailNormalizer. */
    public User(String email, String passwordHash, String displayName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.locale = DEFAULT_LOCALE;
        this.timeZone = DEFAULT_TIME_ZONE;
        this.theme = DEFAULT_THEME;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getLocale() {
        return locale;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public Theme getTheme() {
        return theme;
    }

    /** displayName đã cắt khoảng trắng, 1–100 ký tự (DTO kiểm). */
    public void changeDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /** passwordHash là BCrypt. */
    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void changeTheme(Theme theme) {
        this.theme = theme;
    }

    /** locale phải là "vi" hoặc "en" (DTO và ràng buộc DB ck_users_locale kiểm). */
    public void changeLocale(String locale) {
        this.locale = locale;
    }

    /** timeZone phải là id IANA hợp lệ (@IanaTimeZone ở DTO kiểm). */
    public void changeTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    /**
     * Về mặc định (POST me/settings/reset): giao diện SYSTEM, ngôn ngữ vi; múi giờ GIỮ NGUYÊN (spec mục 12.1).
     * M4 thêm ở đây: remindersEnabled = true, reminderOffsetMinutes = 0, allDayReminderTime = 09:00.
     */
    public void resetSettings() {
        this.theme = DEFAULT_THEME;
        this.locale = DEFAULT_LOCALE;
    }
}
