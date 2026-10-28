package com.taskly.service;

import com.taskly.integration.MailDeliveryException;
import com.taskly.integration.MailSender;
import com.taskly.integration.OutgoingMail;
import com.taskly.integration.PushSender;
import com.taskly.integration.PushSender.PushDeliveryException;
import com.taskly.integration.PushSender.PushMessage;
import com.taskly.integration.PushSender.PushTokenInvalidException;
import com.taskly.repository.NotificationDeliveryRepository;
import com.taskly.repository.NotificationDeliveryRepository.Row;
import com.taskly.repository.PushTokenRepository;
import com.taskly.security.OpaqueTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hàng đợi push / email (spec 5.8, plan F6-F7): enqueue chống gửi trùng bằng khoá duy nhất (user, kênh, dedupe_key);
 * deliverDue gửi các hàng tới hạn, lỗi thì thử lại 3 lần sau 1 / 5 / 15 phút rồi FAILED. KHÔNG log nội dung / địa chỉ.
 */
@Service
public class NotificationDeliveryService {

    public static final String PUSH = "PUSH";
    public static final String EMAIL = "EMAIL";
    static final int MAX_RETRIES = 3;
    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));
    static final int BATCH = 100;
    private static final Duration LEASE = Duration.ofMinutes(2);

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

    private final NotificationDeliveryRepository deliveries;
    private final PushTokenRepository pushTokens;
    private final PushSender pushSender;
    private final MailSender mailSender;
    private final UserService userService;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public NotificationDeliveryService(NotificationDeliveryRepository deliveries, PushTokenRepository pushTokens,
                                       PushSender pushSender, MailSender mailSender, UserService userService,
                                       JsonMapper jsonMapper, TransactionTemplate tx, Clock clock) {
        this.deliveries = deliveries;
        this.pushTokens = pushTokens;
        this.pushSender = pushSender;
        this.mailSender = mailSender;
        this.userService = userService;
        this.jsonMapper = jsonMapper;
        this.tx = tx;
        this.clock = clock;
    }

    /** true nếu xếp hàng mới; false nếu khoá chống gửi trùng đã có. data (tuỳ chọn) chỉ dùng cho push. */
    public boolean enqueue(UUID userId, String channel, String kind, String dedupeKey, String title, String body,
                           Map<String, String> data) {
        String dataJson = data == null || data.isEmpty() ? null : jsonMapper.writeValueAsString(data);
        Boolean inserted = tx.execute(status -> deliveries.insertIfAbsent(userId, channel, kind, dedupeKey, title,
                body, dataJson, clock.instant()));
        return Boolean.TRUE.equals(inserted);
    }

    /** Gửi mọi hàng tới hạn; trả số hàng đã xử lý. */
    public int deliverDue() {
        Instant now = clock.instant();
        List<Row> due = tx.execute(status -> deliveries.claimDue(now, now.plus(LEASE), BATCH));
        if (due == null) {
            return 0;
        }
        for (Row row : due) {
            boolean ok = false;
            try {
                ok = EMAIL.equals(row.channel()) ? sendEmail(row) : sendPush(row);
            } catch (RuntimeException e) {
                log.warn("Gửi thông báo {} lỗi ({})", row.id(), e.getClass().getSimpleName());
            }
            Instant after = clock.instant();
            int attempts = row.attempts() + 1;
            if (ok) {
                deliveries.recordAttempt(row.id(), attempts, "SENT", after, after);
            } else if (attempts > MAX_RETRIES) {
                deliveries.recordAttempt(row.id(), attempts, "FAILED", after, after);
            } else {
                deliveries.recordAttempt(row.id(), attempts, "PENDING", after.plus(RETRY_DELAYS.get(attempts - 1)), after);
            }
        }
        return due.size();
    }

    private boolean sendEmail(Row row) {
        var user = userService.findById(row.userId());
        if (user.isEmpty()) {
            return true; // tài khoản đã xoá: không còn gì để gửi
        }
        try {
            mailSender.send(new OutgoingMail(user.get().getEmail(), row.title(), row.body()));
            return true;
        } catch (MailDeliveryException e) {
            return false;
        }
    }

    /** Gửi tới mọi thiết bị; token hết hiệu lực bị xoá. Chỉ thử lại khi có lỗi tạm thời VÀ chưa gửi được thiết bị nào. */
    private boolean sendPush(Row row) {
        Map<String, String> data = parseData(row.data());
        int success = 0;
        int failed = 0;
        for (String token : pushTokens.tokensOf(row.userId())) {
            try {
                pushSender.send(new PushMessage(token, row.title(), row.body(), data));
                success++;
            } catch (PushTokenInvalidException e) {
                pushTokens.deleteByHash(OpaqueTokens.sha256Hex(token));
            } catch (PushDeliveryException e) {
                failed++;
            }
        }
        return failed == 0 || success > 0;
    }

    private Map<String, String> parseData(String json) {
        Map<String, String> data = new LinkedHashMap<>();
        if (json != null) {
            JsonNode node = jsonMapper.readTree(json);
            node.properties().forEach(entry -> data.put(entry.getKey(), entry.getValue().asString()));
        }
        return data;
    }
}
