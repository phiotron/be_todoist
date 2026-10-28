package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import com.taskly.common.validation.NavMenuItemsValidator;
import com.taskly.common.validation.QuickAddChipRules;
import com.taskly.common.validation.SettingsTargets;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hibernate.annotations.Formula;

/**
 * Người dùng + cài đặt theo tài khoản (spec bản 3 mục 10). password_hash KHÔNG bao giờ rời tầng service (Controller chỉ
 * thấy UserResponse / SettingsResponse).
 * Từ M5: password_hash null = tài khoản chỉ đăng nhập bằng Google. identities = các liên kết đăng nhập ngoài, thêm / bỏ
 * qua linkIdentity / unlinkIdentity (cascade ALL + orphanRemoval, không có repository riêng); identities là LAZY: chỉ gọi
 * hasPassword/identity/linkedProviders... trên User đang managed bên trong transaction.
 * Các hàm change* gán thẳng giá trị: gán giá trị giống hệt thì Hibernate không thấy "dirty" → không UPDATE →
 * updated_at giữ nguyên (BR-10, spec mục 5.6). Giá trị đã được DTO kiểm (và CHECK ở DB chặn lần cuối).
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    public static final String DEFAULT_LOCALE = "vi";
    public static final String DEFAULT_TIME_ZONE = "Asia/Ho_Chi_Minh";
    public static final Theme DEFAULT_THEME = Theme.TASKLY;
    public static final Appearance DEFAULT_APPEARANCE = Appearance.AUTO;
    public static final int DEFAULT_START_DAY = 1;
    public static final int DEFAULT_NEXT_WEEK = 1;
    public static final int DEFAULT_WEEKEND_START_DAY = 6;
    public static final DateFormat DEFAULT_DATE_FORMAT = DateFormat.DMY;
    public static final TimeFormat DEFAULT_TIME_FORMAT = TimeFormat.H24;
    public static final String DEFAULT_START_PAGE = SettingsTargets.DEFAULT_START_PAGE;
    public static final boolean DEFAULT_SMART_DATE_RECOGNITION = true;
    public static final SwipeAction DEFAULT_SWIPE_LEFT = SwipeAction.SCHEDULE;
    public static final SwipeAction DEFAULT_SWIPE_RIGHT = SwipeAction.COMPLETE;
    public static final List<String> DEFAULT_NAV_TABS = SettingsTargets.DEFAULT_NAV_TABS;
    public static final boolean DEFAULT_REMINDERS_ENABLED = true;
    /** spec 5.8: mặc định 30 phút; null = tắt auto reminder. */
    public static final Integer DEFAULT_AUTO_REMINDER_MINUTES = 30;
    /** M3x (V7): vị trí nút +, Sync home view, Navigation menu. */
    public static final AddButtonPlacement DEFAULT_ADD_BUTTON_PLACEMENT = AddButtonPlacement.RIGHT;
    public static final boolean DEFAULT_SYNC_HOME_VIEW = true;
    public static final boolean DEFAULT_NAV_MENU_SHOW_TASK_COUNT = true;
    public static final List<String> DEFAULT_NAV_MENU_ITEMS = NavMenuItemsValidator.ALL_ITEMS;

    /** M6b: chip Quick Add mặc định + hai công tắc "Show action labels" / "Show description" (mặc định bật). */
    public static final List<String> DEFAULT_QUICK_ADD_CHIPS = QuickAddChipRules.DEFAULT;
    public static final boolean DEFAULT_QUICK_ADD_SHOW_ACTION_LABELS = true;
    public static final boolean DEFAULT_QUICK_ADD_SHOW_DESCRIPTION = true;
    /** M6c: Todoist mặc định tắt "Reset sub-tasks". */
    public static final boolean DEFAULT_RESET_SUBTASKS = false;
    /** M10: mục tiêu mặc định 5 / 30 (app Todoist thật); ngày nghỉ mặc định Thứ Bảy + Chủ nhật [CXM]. */
    public static final int DEFAULT_DAILY_GOAL = 5;
    public static final int DEFAULT_WEEKLY_GOAL = 30;
    public static final String DEFAULT_DAYS_OFF = "6,7";
    /** M12: giữ 15 như M4 (Todoist 30 — chờ người dùng duyệt, contract mục 6 câu 6). */
    public static final int DEFAULT_SNOOZE_MINUTES = 15;
    public static final boolean DEFAULT_PIN_REMINDERS = false;

    /** nav_tabs lưu các khoá nối bằng dấu phẩy (khoá không bao giờ chứa dấu phẩy). */
    private static final String NAV_TABS_SEPARATOR = ",";

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
    @Enumerated(EnumType.STRING)
    @Column(name = "theme", nullable = false, length = 16)
    private Theme theme;

    @Enumerated(EnumType.STRING)
    @Column(name = "appearance", nullable = false, length = 8)
    private Appearance appearance;

    /** 1 = Thứ Hai … 7 = Chủ nhật. */
    @Column(name = "start_day", nullable = false)
    private int startDay;

    @Column(name = "next_week", nullable = false)
    private int nextWeek;

    @Column(name = "weekend_start_day", nullable = false)
    private int weekendStartDay;

    @Enumerated(EnumType.STRING)
    @Column(name = "date_format", nullable = false, length = 3)
    private DateFormat dateFormat;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_format", nullable = false, length = 3)
    private TimeFormat timeFormat;

    @Column(name = "start_page", nullable = false, length = 64)
    private String startPage;

    @Column(name = "smart_date_recognition", nullable = false)
    private boolean smartDateRecognition;

    @Enumerated(EnumType.STRING)
    @Column(name = "swipe_left", nullable = false, length = 10)
    private SwipeAction swipeLeft;

    @Enumerated(EnumType.STRING)
    @Column(name = "swipe_right", nullable = false, length = 10)
    private SwipeAction swipeRight;

    @Column(name = "nav_tabs", nullable = false, columnDefinition = "text")
    private String navTabs;

    @Column(name = "reminders_enabled", nullable = false)
    private boolean remindersEnabled = DEFAULT_REMINDERS_ENABLED;

    @Column(name = "auto_reminder_minutes")
    private Integer autoReminderMinutes = DEFAULT_AUTO_REMINDER_MINUTES;

    @Enumerated(EnumType.STRING)
    @Column(name = "add_button_placement", nullable = false, length = 6)
    private AddButtonPlacement addButtonPlacement = DEFAULT_ADD_BUTTON_PLACEMENT;

    @Column(name = "sync_home_view", nullable = false)
    private boolean syncHomeView = DEFAULT_SYNC_HOME_VIEW;

    @Column(name = "nav_menu_show_task_count", nullable = false)
    private boolean navMenuShowTaskCount = DEFAULT_NAV_MENU_SHOW_TASK_COUNT;

    /** Mục Navigation menu đang hiện, nối dấu phẩy; chuỗi rỗng = ẩn hết. */
    @Column(name = "nav_menu_items", nullable = false, columnDefinition = "text")
    private String navMenuItems = String.join(NAV_TABS_SEPARATOR, DEFAULT_NAV_MENU_ITEMS);
    /** M6b: chip Quick Add nối bằng dấu phẩy như nav_tabs (khoá chip không chứa dấu phẩy). */
    @Column(name = "quick_add_chips", nullable = false, columnDefinition = "text")
    private String quickAddChips = String.join(NAV_TABS_SEPARATOR, DEFAULT_QUICK_ADD_CHIPS);

    @Column(name = "quick_add_show_action_labels", nullable = false)
    private boolean quickAddShowActionLabels = DEFAULT_QUICK_ADD_SHOW_ACTION_LABELS;

    @Column(name = "quick_add_show_description", nullable = false)
    private boolean quickAddShowDescription = DEFAULT_QUICK_ADD_SHOW_DESCRIPTION;
    /** M6c: "Reset sub-tasks" — tự mở lại task con khi hoàn thành task lặp (mặc định tắt). */
    @Column(name = "reset_subtasks", nullable = false)
    private boolean resetSubtasks = DEFAULT_RESET_SUBTASKS;
    // ---- M10: cài đặt Productivity (V14). Không thuộc resetSettings (Todoist không có reset cho nhóm này).
    @Column(name = "daily_goal", nullable = false)
    private int dailyGoal = DEFAULT_DAILY_GOAL;

    @Column(name = "weekly_goal", nullable = false)
    private int weeklyGoal = DEFAULT_WEEKLY_GOAL;

    /** Ngày ISO 1–7 nối bằng dấu phẩy, tăng dần ("" = không nghỉ ngày nào). */
    @Column(name = "days_off", nullable = false, length = 16)
    private String daysOff = DEFAULT_DAYS_OFF;

    @Column(name = "vacation_mode", nullable = false)
    private boolean vacationMode;

    @Column(name = "goal_celebrations", nullable = false)
    private boolean goalCelebrations = true;

    @Column(name = "karma_enabled", nullable = false)
    private boolean karmaEnabled = true;
    // ---- M12 (V16): snooze, pin, thiết bị nhận urgent reminder
    @Column(name = "snooze_minutes", nullable = false)
    private int snoozeMinutes = DEFAULT_SNOOZE_MINUTES;

    @Column(name = "pin_reminders", nullable = false)
    private boolean pinReminders = DEFAULT_PIN_REMINDERS;

    /** null = chưa chọn thiết bị nhận urgent reminder. */
    @Column(name = "urgent_device_id", length = 64)
    private String urgentDeviceId;

    /**
     * Số dòng user_totp ĐÃ BẬT của user (0 hoặc 1) — chỉ đọc, tính lúc nạp entity (spec 12.1: GET me kèm
     * twoFactorEnabled). Bật / tắt 2FA ghi ở TwoFactorService; entity đã nạp trước đó trong cùng transaction giữ giá trị cũ.
     */
    @Formula("(select count(*) from user_totp t where t.user_id = id and t.enabled_at is not null)")
    private long enabledTwoFactorCount;

    // ---- M8b: ảnh đại diện (file trên đĩa server qua FileStorage; V12). Đổi qua setAvatar / clearAvatar.
    @Column(name = "avatar_key", length = 80)
    private String avatarKey;

    @Column(name = "avatar_content_type", length = 32)
    private String avatarContentType;

    @Column(name = "avatar_updated_at")
    private java.time.Instant avatarUpdatedAt;

    protected User() {
        // cho JPA
    }

    /** email phải đã được chuẩn hoá bằng EmailNormalizer. passwordHash null = tài khoản chỉ có Google. Mọi cài đặt nhận giá trị mặc định của spec mục 10. */
    public User(String email, String passwordHash, String displayName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.locale = DEFAULT_LOCALE;
        this.timeZone = DEFAULT_TIME_ZONE;
        applyDefaultSettings();
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

    public Theme getTheme() {
        return theme;
    }

    public Appearance getAppearance() {
        return appearance;
    }

    public int getStartDay() {
        return startDay;
    }

    public int getNextWeek() {
        return nextWeek;
    }

    public int getWeekendStartDay() {
        return weekendStartDay;
    }

    public DateFormat getDateFormat() {
        return dateFormat;
    }

    public TimeFormat getTimeFormat() {
        return timeFormat;
    }

    public String getStartPage() {
        return startPage;
    }

    public boolean isSmartDateRecognition() {
        return smartDateRecognition;
    }

    public SwipeAction getSwipeLeft() {
        return swipeLeft;
    }

    public SwipeAction getSwipeRight() {
        return swipeRight;
    }

    public boolean isTwoFactorEnabled() {
        return enabledTwoFactorCount > 0;
    }

    /** Danh sách khoá tab theo thứ tự (không sửa được). */
    public List<String> getNavTabs() {
        return List.of(navTabs.split(NAV_TABS_SEPARATOR));
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

    public void changeAppearance(Appearance appearance) {
        this.appearance = appearance;
    }

    /** locale phải là "vi" hoặc "en" (DTO và ràng buộc DB ck_users_locale kiểm). */
    public void changeLocale(String locale) {
        this.locale = locale;
    }

    /** timeZone phải là id IANA hợp lệ (@IanaTimeZone ở DTO kiểm). */
    public void changeTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public void changeStartDay(int startDay) {
        this.startDay = startDay;
    }

    public void changeNextWeek(int nextWeek) {
        this.nextWeek = nextWeek;
    }

    public void changeWeekendStartDay(int weekendStartDay) {
        this.weekendStartDay = weekendStartDay;
    }

    public void changeDateFormat(DateFormat dateFormat) {
        this.dateFormat = dateFormat;
    }

    public void changeTimeFormat(TimeFormat timeFormat) {
        this.timeFormat = timeFormat;
    }

    /** startPage đã qua @StartPage (định dạng); id ghim đọc được do SettingsService kiểm (Task 13b). */
    public void changeStartPage(String startPage) {
        this.startPage = startPage;
    }

    public void changeSmartDateRecognition(boolean smartDateRecognition) {
        this.smartDateRecognition = smartDateRecognition;
    }

    public void changeSwipeLeft(SwipeAction swipeLeft) {
        this.swipeLeft = swipeLeft;
    }

    public void changeSwipeRight(SwipeAction swipeRight) {
        this.swipeRight = swipeRight;
    }

    /** navTabs đã qua @NavTabs. Lưu dạng nối dấu phẩy → cùng danh sách thì cùng chuỗi → không UPDATE. */
    public void changeNavTabs(List<String> navTabs) {
        this.navTabs = String.join(NAV_TABS_SEPARATOR, navTabs);
    }

    /**
     * Về mặc định (POST me/settings/reset, spec 12.1): mọi cài đặt mục 10 kể cả ngôn ngữ vi; múi giờ GIỮ NGUYÊN.
     * M4 thêm ở cuối hàm: remindersEnabled = true, autoReminderMinutes = 30.
     */
    public void resetSettings() {
        this.locale = DEFAULT_LOCALE;
        applyDefaultSettings();
        resetReminderSettings();
        resetNavigationSettings();
        resetQuickAddSettings();
    }

    /** M6b: thứ tự chip Quick Add (không sửa được). */
    public List<String> getQuickAddChips() {
        return List.of(quickAddChips.split(NAV_TABS_SEPARATOR));
    }

    public boolean isQuickAddShowActionLabels() {
        return quickAddShowActionLabels;
    }

    public boolean isQuickAddShowDescription() {
        return quickAddShowDescription;
    }

    /** chips đã qua @QuickAddChips. Cùng danh sách → cùng chuỗi → không UPDATE. */
    public void changeQuickAddChips(List<String> chips) {
        this.quickAddChips = String.join(NAV_TABS_SEPARATOR, chips);
    }

    public void changeQuickAddShowActionLabels(boolean show) {
        this.quickAddShowActionLabels = show;
    }

    public void changeQuickAddShowDescription(boolean show) {
        this.quickAddShowDescription = show;
    }

    /** M6b: về mặc định (spec 12.1 reset). resetSettings() gọi hàm này. */
    public void resetQuickAddSettings() {
        this.quickAddChips = String.join(NAV_TABS_SEPARATOR, DEFAULT_QUICK_ADD_CHIPS);
        this.quickAddShowActionLabels = DEFAULT_QUICK_ADD_SHOW_ACTION_LABELS;
        this.quickAddShowDescription = DEFAULT_QUICK_ADD_SHOW_DESCRIPTION;
        this.resetSubtasks = DEFAULT_RESET_SUBTASKS; // M6c
    }

    public boolean isResetSubtasks() {
        return resetSubtasks;
    }

    public void changeResetSubtasks(boolean resetSubtasks) {
        this.resetSubtasks = resetSubtasks;
    }

    public boolean isRemindersEnabled() {
        return remindersEnabled;
    }

    /** null = auto reminder đang tắt. */
    public Integer getAutoReminderMinutes() {
        return autoReminderMinutes;
    }

    /** Gán lại giá trị cũ không làm Hibernate ghi (dirty checking) → updated_at giữ nguyên. */
    public void changeRemindersEnabled(boolean enabled) {
        this.remindersEnabled = enabled;
    }

    /** minutes đã được @AutoReminderMinutes kiểm ở DTO; null = tắt auto reminder. */
    public void changeAutoReminderMinutes(Integer minutes) {
        this.autoReminderMinutes = minutes;
    }

    /** Về mặc định (spec 12.1 reset): nhắc bật, auto reminder 30 phút. resetSettings() gọi hàm này. */
    public void resetReminderSettings() {
        this.remindersEnabled = DEFAULT_REMINDERS_ENABLED;
        this.autoReminderMinutes = DEFAULT_AUTO_REMINDER_MINUTES;
        // M12
        this.snoozeMinutes = DEFAULT_SNOOZE_MINUTES;
        this.pinReminders = DEFAULT_PIN_REMINDERS;
        this.urgentDeviceId = null;
    }

    public int getSnoozeMinutes() {
        return snoozeMinutes;
    }

    public boolean isPinReminders() {
        return pinReminders;
    }

    /** null = chưa chọn thiết bị nhận urgent reminder. */
    public String getUrgentDeviceId() {
        return urgentDeviceId;
    }

    /** minutes đã được @SnoozeMinutes kiểm ở DTO (CHECK ck_users_snooze_minutes chặn lần cuối). */
    public void changeSnoozeMinutes(int minutes) {
        this.snoozeMinutes = minutes;
    }

    public void changePinReminders(boolean pinReminders) {
        this.pinReminders = pinReminders;
    }

    /** deviceId đã qua @Pattern ở DTO; null = bỏ chọn. */
    public void changeUrgentDeviceId(String deviceId) {
        this.urgentDeviceId = deviceId;
    }

    public AddButtonPlacement getAddButtonPlacement() {
        return addButtonPlacement;
    }

    public boolean isSyncHomeView() {
        return syncHomeView;
    }

    public boolean isNavMenuShowTaskCount() {
        return navMenuShowTaskCount;
    }

    /** Mục Navigation menu đang hiện, theo thứ tự (không sửa được); rỗng = ẩn hết. */
    public List<String> getNavMenuItems() {
        return navMenuItems.isEmpty() ? List.of() : List.of(navMenuItems.split(NAV_TABS_SEPARATOR));
    }

    public void changeAddButtonPlacement(AddButtonPlacement addButtonPlacement) {
        this.addButtonPlacement = addButtonPlacement;
    }

    public void changeSyncHomeView(boolean syncHomeView) {
        this.syncHomeView = syncHomeView;
    }

    public void changeNavMenuShowTaskCount(boolean navMenuShowTaskCount) {
        this.navMenuShowTaskCount = navMenuShowTaskCount;
    }

    /** items đã qua @NavMenuItems. Cùng danh sách → cùng chuỗi → không UPDATE. */
    public void changeNavMenuItems(List<String> items) {
        this.navMenuItems = String.join(NAV_TABS_SEPARATOR, items);
    }

    /** M3x — về mặc định (spec 12.1 reset): nút + bên phải, đồng bộ trang mở đầu, menu đủ 6 mục có đếm task. */
    public void resetNavigationSettings() {
        this.addButtonPlacement = DEFAULT_ADD_BUTTON_PLACEMENT;
        this.syncHomeView = DEFAULT_SYNC_HOME_VIEW;
        this.navMenuShowTaskCount = DEFAULT_NAV_MENU_SHOW_TASK_COUNT;
        this.navMenuItems = String.join(NAV_TABS_SEPARATOR, DEFAULT_NAV_MENU_ITEMS);
    }

    // ---- M10: Productivity

    public int getDailyGoal() {
        return dailyGoal;
    }

    public int getWeeklyGoal() {
        return weeklyGoal;
    }

    /** Ngày nghỉ ISO (1 = Thứ Hai … 7 = Chủ nhật), tăng dần. */
    public List<Integer> getDaysOff() {
        return daysOff.isEmpty() ? List.of()
                : java.util.Arrays.stream(daysOff.split(NAV_TABS_SEPARATOR)).map(Integer::valueOf).toList();
    }

    public boolean isVacationMode() {
        return vacationMode;
    }

    public boolean isGoalCelebrations() {
        return goalCelebrations;
    }

    public boolean isKarmaEnabled() {
        return karmaEnabled;
    }

    public void changeDailyGoal(int dailyGoal) {
        this.dailyGoal = dailyGoal;
    }

    public void changeWeeklyGoal(int weeklyGoal) {
        this.weeklyGoal = weeklyGoal;
    }

    /** days đã qua @DaysOff (1–7, không trùng, ≤ 6); lưu tăng dần → cùng tập thì cùng chuỗi → không UPDATE. */
    public void changeDaysOff(List<Integer> days) {
        this.daysOff = days.stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(NAV_TABS_SEPARATOR));
    }

    public void changeVacationMode(boolean vacationMode) {
        this.vacationMode = vacationMode;
    }

    public void changeGoalCelebrations(boolean goalCelebrations) {
        this.goalCelebrations = goalCelebrations;
    }

    public void changeKarmaEnabled(boolean karmaEnabled) {
        this.karmaEnabled = karmaEnabled;
    }

    private void applyDefaultSettings() {
        this.theme = DEFAULT_THEME;
        this.appearance = DEFAULT_APPEARANCE;
        this.startDay = DEFAULT_START_DAY;
        this.nextWeek = DEFAULT_NEXT_WEEK;
        this.weekendStartDay = DEFAULT_WEEKEND_START_DAY;
        this.dateFormat = DEFAULT_DATE_FORMAT;
        this.timeFormat = DEFAULT_TIME_FORMAT;
        this.startPage = DEFAULT_START_PAGE;
        this.smartDateRecognition = DEFAULT_SMART_DATE_RECOGNITION;
        this.swipeLeft = DEFAULT_SWIPE_LEFT;
        this.swipeRight = DEFAULT_SWIPE_RIGHT;
        this.navTabs = String.join(NAV_TABS_SEPARATOR, DEFAULT_NAV_TABS);
    }

    // ---- M8b: ảnh đại diện

    public String getAvatarKey() {
        return avatarKey;
    }

    public String getAvatarContentType() {
        return avatarContentType;
    }

    public java.time.Instant getAvatarUpdatedAt() {
        return avatarUpdatedAt;
    }

    /** Thay ảnh: trigger V12 đưa khoá cũ vào hàng đợi dọn file. */
    public void setAvatar(String storageKey, String contentType, java.time.Instant updatedAt) {
        this.avatarKey = storageKey;
        this.avatarContentType = contentType;
        this.avatarUpdatedAt = updatedAt;
    }

    public void clearAvatar() {
        setAvatar(null, null, null);
    }
}
