package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Người dùng. password_hash KHÔNG bao giờ rời tầng service (Controller chỉ thấy UserResponse). */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    public static final String DEFAULT_LOCALE = "vi";
    public static final String DEFAULT_TIME_ZONE = "Asia/Ho_Chi_Minh";

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
}
