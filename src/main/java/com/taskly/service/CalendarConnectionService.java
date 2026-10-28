package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.config.CalendarProperties;
import com.taskly.dto.calendar.CalendarStatusResponse;
import com.taskly.dto.calendar.ConnectCalendarResponse;
import com.taskly.dto.calendar.UpdateCalendarRequest;
import com.taskly.entity.CalendarConnection;
import com.taskly.entity.CalendarProvider;
import com.taskly.integration.calendar.CalendarAccount;
import com.taskly.integration.calendar.CalendarAuthException;
import com.taskly.integration.calendar.CalendarClient;
import com.taskly.integration.calendar.CalendarClients;
import com.taskly.integration.calendar.CalendarTokens;
import com.taskly.repository.CalendarConnectionRepository;
import com.taskly.security.CalendarConnectStates;
import com.taskly.security.ExternalServiceUnavailableException;
import com.taskly.security.InvalidExternalTokenException;
import com.taskly.security.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kết nối Google Calendar / Outlook (M13b): OAuth phía server (client secret không rời BE), token lưu mã hoá bằng
 * SecretCipher (aad = userId), chỉ 1 kết nối / tài khoản. KHÔNG log token.
 */
@Service
@Transactional
public class CalendarConnectionService {

    private static final Logger log = LoggerFactory.getLogger(CalendarConnectionService.class);

    /** Mã lỗi gắn vào deep link taskly://calendar/connected?error=… (app dịch sang câu chữ). */
    public static final String ERROR_DENIED = "denied";
    public static final String ERROR_INVALID_STATE = "invalid-state";
    public static final String ERROR_ALREADY_CONNECTED = "already-connected";
    public static final String ERROR_UNAVAILABLE = "unavailable";

    private final CalendarConnectionRepository connections;
    private final CalendarClients clients;
    private final CalendarConnectStates states;
    private final SecretCipher cipher;
    private final CalendarProperties properties;
    private final UserService userService;
    private final Clock clock;

    public CalendarConnectionService(CalendarConnectionRepository connections, CalendarClients clients,
                                     CalendarConnectStates states, SecretCipher cipher, CalendarProperties properties,
                                     UserService userService, Clock clock) {
        this.connections = connections;
        this.clients = clients;
        this.states = states;
        this.cipher = cipher;
        this.properties = properties;
        this.userService = userService;
        this.clock = clock;
    }

    /** Access token còn hạn của một kết nối (đã làm mới nếu cần). */
    public record Access(CalendarConnection connection, CalendarClient client, String accessToken) {

        @Override
        public String toString() {
            return "Access[connection=" + connection.getId() + ", accessToken=***]";
        }
    }

    @Transactional(readOnly = true)
    public CalendarStatusResponse status(UUID userId) {
        return connections.findByUserId(userId).map(CalendarConnectionService::toStatus)
                .orElseGet(CalendarStatusResponse::disconnected);
    }

    /** POST me/calendar/connect → URL đồng ý OAuth. Đã nối → 409; nhà cung cấp chưa cấu hình → 503. */
    @Transactional(readOnly = true)
    public ConnectCalendarResponse connect(UUID userId, CalendarProvider provider) {
        userService.requireUser(userId);
        if (connections.findByUserId(userId).isPresent()) {
            throw alreadyConnected();
        }
        CalendarClient client = clients.get(provider);
        if (!client.configured()) {
            throw ApiException.serviceUnavailable(ErrorMessage.CALENDAR_NOT_CONFIGURED)
                    .withCode(ProblemCodes.CALENDAR_NOT_CONFIGURED);
        }
        return new ConnectCalendarResponse(client.authorizationUrl(states.issue(userId, provider),
                properties.redirectUri()));
    }

    /**
     * GET calendar/oauth/callback (công khai). Trả null khi nối xong, hoặc một mã ERROR_* cho deep link. Không ném lỗi
     * HTTP: trình duyệt luôn được chuyển về app.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String completeOAuth(String state, String code, String error) {
        CalendarConnectStates.State verified;
        try {
            verified = states.verify(state);
        } catch (InvalidExternalTokenException ex) {
            return ERROR_INVALID_STATE;
        }
        if (error != null || code == null || code.isBlank()) {
            return ERROR_DENIED;
        }
        CalendarClient client = clients.get(verified.provider());
        CalendarAccount account;
        try {
            account = client.exchangeCode(code, properties.redirectUri());
        } catch (CalendarAuthException ex) {
            return ERROR_DENIED;
        } catch (ExternalServiceUnavailableException ex) {
            log.warn("Không đổi được code OAuth lịch {}: {}", verified.provider(), causeName(ex));
            return ERROR_UNAVAILABLE;
        }
        try {
            saveNewConnection(verified, account);
            return null;
        } catch (DataIntegrityViolationException ex) { // nối song song / user đã xoá tài khoản
            return ERROR_ALREADY_CONNECTED;
        } catch (ApiException ex) {
            return ex.getStatusCode().value() == 409 ? ERROR_ALREADY_CONNECTED : ERROR_INVALID_STATE;
        }
    }

    private void saveNewConnection(CalendarConnectStates.State state, CalendarAccount account) {
        userService.requireUser(state.userId());
        if (connections.findByUserId(state.userId()).isPresent()) {
            throw alreadyConnected();
        }
        CalendarConnection connection = new CalendarConnection(state.userId(), state.provider(),
                account.accountEmail());
        CalendarTokens tokens = account.tokens();
        connection.storeTokens(encrypt(state.userId(), tokens.accessToken()),
                encrypt(state.userId(), tokens.refreshToken()), tokens.expiresAt());
        connections.saveAndFlush(connection);
    }

    public CalendarStatusResponse update(UUID userId, UpdateCalendarRequest request) {
        CalendarConnection connection = require(userId);
        if (request.showEvents() != null) {
            connection.changeShowEvents(request.showEvents());
        }
        if (request.syncTasks() != null) {
            connection.changeSyncTasks(request.syncTasks());
        }
        return toStatus(connection);
    }

    /** DELETE me/calendar: xoá kết nối + liên kết sự kiện (cascade). Lịch "Taskly" bên nhà cung cấp giữ nguyên [CXM]. */
    public void disconnect(UUID userId) {
        connections.delete(require(userId));
    }

    /**
     * Access token còn hạn: hết hạn (trừ biên an toàn) → làm mới và lưu bản mã mới. Quyền bị thu hồi → đánh dấu
     * needsReauth (commit riêng) rồi ném CalendarAuthException.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = CalendarAuthException.class)
    public Access access(UUID connectionId) {
        CalendarConnection connection = connections.findById(connectionId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.CALENDAR_NOT_CONNECTED));
        CalendarClient client = clients.get(connection.getProvider());
        if (connection.isNeedsReauth()) {
            throw new CalendarAuthException();
        }
        UUID userId = connection.getUserId();
        Instant expiresAt = connection.getTokenExpiresAt();
        if (expiresAt == null || expiresAt.isAfter(Instant.now(clock).plus(properties.tokenRefreshMargin()))) {
            return new Access(connection, client, decrypt(userId, connection.getAccessTokenEnc()));
        }
        try {
            CalendarTokens fresh = client.refresh(decrypt(userId, connection.getRefreshTokenEnc()));
            connection.storeTokens(encrypt(userId, fresh.accessToken()), encrypt(userId, fresh.refreshToken()),
                    fresh.expiresAt());
            return new Access(connection, client, fresh.accessToken());
        } catch (CalendarAuthException ex) {
            connection.markNeedsReauth();
            throw ex;
        }
    }

    /** Đánh dấu cần nối lại khi API lịch trả 401 / 403 giữa chừng. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markNeedsReauth(UUID connectionId) {
        connections.findById(connectionId).ifPresent(CalendarConnection::markNeedsReauth);
    }

    @Transactional(readOnly = true)
    public Optional<CalendarConnection> find(UUID userId) {
        return connections.findByUserId(userId);
    }

    private CalendarConnection require(UUID userId) {
        return connections.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.CALENDAR_NOT_CONNECTED));
    }

    private static ApiException alreadyConnected() {
        return ApiException.conflict(ErrorMessage.CALENDAR_ALREADY_CONNECTED)
                .withCode(ProblemCodes.CALENDAR_ALREADY_CONNECTED);
    }

    static CalendarStatusResponse toStatus(CalendarConnection connection) {
        return new CalendarStatusResponse(true, connection.getProvider(), connection.getAccountEmail(),
                connection.isShowEvents(), connection.isSyncTasks(), connection.isNeedsReauth(),
                connection.getLastSyncedAt());
    }

    private String encrypt(UUID userId, String value) {
        return value == null ? null : cipher.encrypt(userId, value.getBytes(StandardCharsets.UTF_8));
    }

    private String decrypt(UUID userId, String stored) {
        return stored == null ? null : new String(cipher.decrypt(userId, stored), StandardCharsets.UTF_8);
    }

    static String causeName(Throwable ex) {
        return ex.getCause() == null ? "unknown" : ex.getCause().getClass().getSimpleName();
    }
}
