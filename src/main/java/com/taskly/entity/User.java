package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Người dùng. password_hash KHÔNG bao giờ rời tầng service (Controller chỉ thấy UserResponse).
 * Từ M5: password_hash null = tài khoản chỉ đăng nhập bằng Google. identities = các liên kết đăng nhập ngoài,
 * thêm / bỏ qua linkIdentity / unlinkIdentity (cascade ALL + orphanRemoval, không có repository riêng).
 * identities là LAZY: chỉ gọi hasPassword/identity/linkedProviders... trên User đang managed bên trong transaction.
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    public static final String DEFAULT_LOCALE = "vi";
    public static final String DEFAULT_TIME_ZONE = "Asia/Ho_Chi_Minh";

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    /** BCrypt; null với tài khoản chỉ có Google (M5). */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<UserIdentity> identities = new ArrayList<>();

    protected User() {
        // cho JPA
    }

    /** email phải đã được chuẩn hoá bằng EmailNormalizer; passwordHash null = tài khoản chỉ có Google. */
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

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public Optional<UserIdentity> identity(IdentityProvider provider) {
        return identities.stream().filter(identity -> identity.getProvider() == provider).findFirst();
    }

    /** Các nhà cung cấp đã liên kết, sắp theo thứ tự enum (ổn định cho response). */
    public List<IdentityProvider> linkedProviders() {
        return identities.stream().map(UserIdentity::getProvider).distinct().sorted().toList();
    }

    /** Thêm liên kết (lưu khi flush nhờ cascade). Mỗi nhà cung cấp tối đa một liên kết. */
    public UserIdentity linkIdentity(IdentityProvider provider, String subject, String email) {
        if (identity(provider).isPresent()) {
            throw new IllegalStateException("Tài khoản đã có liên kết " + provider);
        }
        UserIdentity identity = new UserIdentity(this, provider, subject, email);
        identities.add(identity);
        return identity;
    }

    /** Bỏ liên kết (dòng bị xoá khi flush nhờ orphanRemoval). Trả false nếu chưa liên kết. */
    public boolean unlinkIdentity(IdentityProvider provider) {
        return identities.removeIf(identity -> identity.getProvider() == provider);
    }
}
