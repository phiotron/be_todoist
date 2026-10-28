package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.email.InboundEmailRequest;
import com.taskly.dto.email.InboundEmailResponse;
import com.taskly.dto.task.CreateTaskRequest;
import com.taskly.entity.EmailInbox;
import com.taskly.entity.Label;
import com.taskly.entity.Task;
import com.taskly.repository.EmailInboxRepository;
import com.taskly.repository.LabelRepository;
import com.taskly.security.EmailInboundRateLimiter;
import com.taskly.security.OpaqueTokens;
import com.taskly.security.RateLimiter;
import com.taskly.service.email.EmailSubjectParser;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Xá»­ lÃ½ má»™t thÆ° Ä‘áº¿n tá»« webhook inbound (M13a) â€” chá»¯ kÃ½ Ä‘Ã£ kiá»ƒm á»Ÿ controller. Vá»›i Má»–I Ä‘á»‹a chá»‰ Taskly trong {@code to}:
 * tra token â†’ giá»›i háº¡n táº§n suáº¥t theo Ä‘á»‹a chá»‰ â†’ trong Má»˜T transaction riÃªng: ghi dáº¥u chá»‘ng trÃ¹ng (inbox, message-id) rá»“i
 * táº¡o task (Ä‘á»‹a chá»‰ dá»± Ã¡n / Inbox) hoáº·c ná»‘i ná»™i dung vÃ o mÃ´ táº£ task (Ä‘á»‹a chá»‰ task). Lá»—i nghiá»‡p vá»¥ cá»§a má»™t ngÆ°á»i nháº­n
 * (404 â†’ ignored; 400 / 409 / 422 â†’ rejected) rollback riÃªng pháº§n Ä‘Ã³ (ká»ƒ cáº£ dáº¥u chá»‘ng trÃ¹ng, Ä‘á»ƒ gá»­i láº¡i Ä‘Æ°á»£c) vÃ  khÃ´ng
 * áº£nh hÆ°á»Ÿng ngÆ°á»i nháº­n khÃ¡c.
 *
 * <p>QUYáº¾T Äá»ŠNH Táº M (M8b chÆ°a cÃ³ comment): ná»™i dung thÆ° vÃ o MÃ” Táº¢ task thay vÃ¬ comment.
 */
@Service
public class EmailInboundService {

    static final int MAX_DESCRIPTION = 16383;
    private static final Pattern ADDRESS = Pattern.compile("[^\\s<>,;\"'()\\[\\]]+@[^\\s<>,;\"'()\\[\\]]+");

    private enum Outcome { CREATED, APPENDED, DUPLICATE, IGNORED, REJECTED }

    private final EmailInboxService inboxService;
    private final EmailInboxRepository inboxRepository;
    private final TaskService taskService;
    private final UserService userService;
    private final LabelRepository labelRepository;
    private final EmailInboundRateLimiter rateLimiter;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public EmailInboundService(EmailInboxService inboxService, EmailInboxRepository inboxRepository,
                               TaskService taskService, UserService userService, LabelRepository labelRepository,
                               EmailInboundRateLimiter rateLimiter, PlatformTransactionManager transactionManager,
                               Clock clock) {
        this.inboxService = inboxService;
        this.inboxRepository = inboxRepository;
        this.taskService = taskService;
        this.userService = userService;
        this.labelRepository = labelRepository;
        this.rateLimiter = rateLimiter;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public InboundEmailResponse receive(InboundEmailRequest mail) {
        String messageIdHash = OpaqueTokens.sha256Hex(mail.messageId().strip());
        int created = 0;
        int appended = 0;
        int duplicates = 0;
        int ignored = 0;
        int rejected = 0;
        int rateLimited = 0;
        long retryAfter = 0;
        for (String token : recipientTokens(mail.to())) {
            Optional<EmailInbox> inbox = inboxService.findByToken(token);
            if (inbox.isEmpty()) {
                ignored++;
                continue;
            }
            RateLimiter.Decision decision = rateLimiter.tryAddress(inbox.get().getId());
            if (!decision.allowed()) {
                rateLimited++;
                retryAfter = Math.max(retryAfter, decision.retryAfterSeconds());
                continue;
            }
            switch (deliver(inbox.get(), messageIdHash, mail)) {
                case CREATED -> created++;
                case APPENDED -> appended++;
                case DUPLICATE -> duplicates++;
                case IGNORED -> ignored++;
                case REJECTED -> rejected++;
            }
        }
        if (rateLimited > 0 && created + appended + duplicates + rejected == 0) {
            // má»i ngÆ°á»i nháº­n há»£p lá»‡ Ä‘á»u vÆ°á»£t giá»›i háº¡n â†’ 429 Ä‘á»ƒ dá»‹ch vá»¥ nháº­n thÆ° gá»­i láº¡i sau (khÃ´ng máº¥t thÆ°)
            throw ApiException.tooManyRequests(ErrorMessage.TOO_MANY_REQUESTS, retryAfter);
        }
        return new InboundEmailResponse(created, appended, duplicates, ignored, rejected, rateLimited);
    }

    /** Token cá»§a cÃ¡c Ä‘á»‹a chá»‰ thuá»™c tÃªn miá»n nháº­n thÆ°, chá»¯ thÆ°á»ng, khÃ´ng trÃ¹ng, giá»¯ thá»© tá»± xuáº¥t hiá»‡n. */
    Set<String> recipientTokens(List<String> recipients) {
        Set<String> tokens = new LinkedHashSet<>();
        if (recipients == null) {
            return tokens;
        }
        String domain = inboxService.domain();
        for (String recipient : recipients) {
            if (recipient == null) {
                continue;
            }
            Matcher matcher = ADDRESS.matcher(recipient);
            while (matcher.find()) {
                String address = matcher.group().toLowerCase(Locale.ROOT);
                int at = address.lastIndexOf('@');
                if (address.substring(at + 1).equals(domain)) {
                    tokens.add(address.substring(0, at));
                }
            }
        }
        return tokens;
    }

    private Outcome deliver(EmailInbox inbox, String messageIdHash, InboundEmailRequest mail) {
        try {
            return transaction.execute(status -> {
                if (inboxRepository.markReceived(inbox.getId(), messageIdHash, clock.instant()) == 0) {
                    return Outcome.DUPLICATE;
                }
                return inbox.getProjectId() != null ? createTask(inbox, mail) : appendToTask(inbox, mail);
            });
        } catch (ApiException e) {
            return e.getStatusCode().value() == 404 ? Outcome.IGNORED : Outcome.REJECTED;
        }
    }

    private Outcome createTask(EmailInbox inbox, InboundEmailRequest mail) {
        UUID userId = inbox.getUserId();
        LocalDate today = TaskDueRules.today(clock.instant(), userService.timeZoneOf(userId));
        EmailSubjectParser.Parsed parsed = EmailSubjectParser.parse(mail.subject(), today, labelResolver(userId));
        String body = normalizeBody(mail.text());
        String content = parsed.content();
        if (content.isBlank()) {
            content = EmailSubjectParser.truncate(firstLine(body), EmailSubjectParser.MAX_CONTENT);
        }
        if (content.isBlank()) {
            return Outcome.IGNORED;
        }
        CreateTaskRequest request = new CreateTaskRequest(null, inbox.getProjectId(), null, null, content,
                EmailSubjectParser.truncate(body, MAX_DESCRIPTION), parsed.priority(), parsed.dueDate(),
                parsed.dueDatetime(), null, null, null, parsed.labelIds(),
                null, null, null, null);
        taskService.create(userId, request);
        return Outcome.CREATED;
    }

    private Outcome appendToTask(EmailInbox inbox, InboundEmailRequest mail) {
        String subject = mail.subject() == null ? "" : mail.subject().strip();
        String body = normalizeBody(mail.text());
        String block = subject.isEmpty() ? body : body.isEmpty() ? subject : subject + "\n" + body;
        if (block.isEmpty()) {
            return Outcome.IGNORED;
        }
        Task task = taskService.requireWritable(inbox.getUserId(), inbox.getTaskId());
        task = taskService.lockAndRecheck(task); // khoÃ¡ dá»± Ã¡n rá»“i Ä‘á»c láº¡i (cÃ¹ng cá»•ng PATCH task)
        String current = task.getDescription();
        String next = current.isEmpty() ? block : current + "\n\n" + block;
        task.setDescription(EmailSubjectParser.truncate(next, MAX_DESCRIPTION));
        taskService.flush(); // tÄƒng version trong transaction nÃ y
        return Outcome.APPENDED;
    }

    /** Tra nhÃ£n CÃ“ Sáº´N cá»§a user theo tÃªn, khÃ´ng phÃ¢n biá»‡t hoa thÆ°á»ng (â‰¤ 500 nhÃ£n / user â€” náº¡p má»™t láº§n má»—i thÆ°). */
    private Function<String, Optional<UUID>> labelResolver(UUID userId) {
        Map<String, UUID> byName = labelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .collect(Collectors.toMap(label -> label.getName().toLowerCase(Locale.ROOT), Label::getId,
                        (first, second) -> first));
        return name -> Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    private static String normalizeBody(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static String firstLine(String body) {
        for (String line : body.split("\n")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }
}
