package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.config.CalendarProperties;
import com.taskly.dto.calendar.CalendarFeedResponse;
import com.taskly.entity.CalendarFeed;
import com.taskly.entity.Project;
import com.taskly.entity.Task;
import com.taskly.repository.CalendarFeedRepository;
import com.taskly.repository.CalendarTaskRepository;
import com.taskly.repository.ProjectRepository;
import com.taskly.security.AccessPolicy;
import com.taskly.security.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calendar feed iCal (M13b): một link / tài khoản và một link / (tài khoản, dự án). Token 32 byte ngẫu nhiên (base64url)
 * nằm trong URL; DB lưu SHA-256 (tra cứu) + bản mã SecretCipher (hiện lại link cho chủ). Đặt lại → token cũ 404 ngay.
 */
@Service
@Transactional
public class CalendarFeedService {

    static final String EXTENSION = ".ics";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CalendarFeedRepository feeds;
    private final CalendarTaskRepository calendarTasks;
    private final ProjectRepository projectRepository;
    private final ProjectService projectService;
    private final AccessPolicy accessPolicy;
    private final UserService userService;
    private final SecretCipher cipher;
    private final CalendarProperties properties;
    private final Clock clock;

    public CalendarFeedService(CalendarFeedRepository feeds, CalendarTaskRepository calendarTasks,
                               ProjectRepository projectRepository, ProjectService projectService,
                               AccessPolicy accessPolicy, UserService userService, SecretCipher cipher,
                               CalendarProperties properties, Clock clock) {
        this.feeds = feeds;
        this.calendarTasks = calendarTasks;
        this.projectRepository = projectRepository;
        this.projectService = projectService;
        this.accessPolicy = accessPolicy;
        this.userService = userService;
        this.cipher = cipher;
        this.properties = properties;
        this.clock = clock;
    }

    /** POST me/calendar-feeds: link hiện có hoặc tạo mới. Dự án không đọc được → 404. */
    public CalendarFeedResponse getOrCreate(UUID userId, UUID projectId) {
        userService.requireUser(userId);
        if (projectId != null) {
            projectService.requireReadable(userId, projectId);
        }
        Optional<CalendarFeed> existing = find(userId, projectId);
        if (existing.isPresent()) {
            String token = new String(cipher.decrypt(userId, existing.get().getTokenEnc()), StandardCharsets.UTF_8);
            return new CalendarFeedResponse(url(token), projectId);
        }
        String token = newToken();
        feeds.save(new CalendarFeed(userId, projectId, hash(token), encrypt(userId, token)));
        return new CalendarFeedResponse(url(token), projectId);
    }

    /** POST me/calendar-feeds/reset: token mới, link cũ hết hiệu lực. */
    public CalendarFeedResponse reset(UUID userId, UUID projectId) {
        userService.requireUser(userId);
        if (projectId != null) {
            projectService.requireReadable(userId, projectId);
        }
        String token = newToken();
        Optional<CalendarFeed> existing = find(userId, projectId);
        if (existing.isPresent()) {
            existing.get().replaceToken(hash(token), encrypt(userId, token));
        } else {
            feeds.save(new CalendarFeed(userId, projectId, hash(token), encrypt(userId, token)));
        }
        return new CalendarFeedResponse(url(token), projectId);
    }

    /** GET calendar/feed/{token}.ics (công khai). Token sai / đã đặt lại / mất quyền dự án → 404. */
    @Transactional(readOnly = true)
    public String render(String tokenWithExtension) {
        String token = tokenWithExtension.endsWith(EXTENSION)
                ? tokenWithExtension.substring(0, tokenWithExtension.length() - EXTENSION.length())
                : tokenWithExtension;
        CalendarFeed feed = feeds.findByTokenHash(hash(token)).orElseThrow(CalendarFeedService::notFound);
        UUID userId = feed.getUserId();
        ZoneId zone = userService.timeZoneOf(userId);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate from = today.minusDays(properties.windowPastDays());
        LocalDate to = today.plusDays(properties.windowFutureDays());
        String name;
        List<Task> tasks;
        if (feed.getProjectId() == null) {
            name = "Taskly";
            tasks = calendarTasks.findScheduled(accessPolicy.readableWorkspaceIds(userId), from, to,
                    properties.maxTasks());
        } else {
            Project project = projectRepository.findById(feed.getProjectId()).orElseThrow(CalendarFeedService::notFound);
            if (!accessPolicy.canRead(userId, project.getWorkspace().getId())) {
                throw notFound();
            }
            name = "Taskly: " + project.getName();
            tasks = calendarTasks.findScheduledInProject(project.getId(), from, to, properties.maxTasks());
        }
        return IcsWriter.write(name, tasks, zone, properties.defaultEventMinutes(), clock.instant());
    }

    private Optional<CalendarFeed> find(UUID userId, UUID projectId) {
        return projectId == null ? feeds.findByUserIdAndProjectIdIsNull(userId)
                : feeds.findByUserIdAndProjectId(userId, projectId);
    }

    private String url(String token) {
        return properties.feedBaseUrl() + token + EXTENSION;
    }

    private String encrypt(UUID userId, String token) {
        return cipher.encrypt(userId, token.getBytes(StandardCharsets.UTF_8));
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound(ErrorMessage.CALENDAR_FEED_NOT_FOUND);
    }
}
