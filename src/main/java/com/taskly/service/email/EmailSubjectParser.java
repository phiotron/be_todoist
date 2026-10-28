package com.taskly.service.email;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Tách cú pháp trong tiêu đề thư (M13a, như Todoist "forward emails"): THUẦN, "hôm nay" và cách tra nhãn truyền vào.
 * <ul>
 *   <li>tiền tố {@code Re:} / {@code Fw:} / {@code Fwd:} (lặp, không phân biệt hoa thường) bị bỏ;</li>
 *   <li>{@code p1}…{@code p4} → priority (lần cuối thắng); {@code p5}… giữ nguyên trong tên;</li>
 *   <li>{@code @tên} khớp nhãn CÓ SẴN (resolver) → gắn nhãn; nhãn lạ giữ nguyên trong tên (mặc định tạm, không tự tạo);</li>
 *   <li>{@code date <biểu thức>} → due (EmailDateParser, lấy TIỀN TỐ DÀI NHẤT đọc được, dừng ở token cú pháp kế tiếp;
 *   phần thừa trả lại tên); không đọc được → chữ "date …" giữ nguyên; nhiều lần → lần cuối thắng;</li>
 *   <li>{@code +người}: chưa có giao việc (M8b) → giữ nguyên trong tên.</li>
 * </ul>
 * Tên = các token còn lại nối bằng một dấu cách, cắt 500 ký tự (giới hạn content của task).
 */
public final class EmailSubjectParser {

    public static final int MAX_CONTENT = 500;

    /** priority null = mặc định (P4); dueDate / dueDatetime: tối đa một cái khác null. */
    public record Parsed(String content, Integer priority, List<UUID> labelIds, LocalDate dueDate,
                         LocalDateTime dueDatetime) {
    }

    private static final Pattern REPLY_PREFIX = Pattern.compile("^(?i:re|fwd?|fw)\\s*:\\s*");
    private static final Pattern PRIORITY = Pattern.compile("(?i)p([1-4])");
    private static final Pattern SYNTAX = Pattern.compile("(?i)p\\d|@\\S+|\\+\\S+|date");

    private EmailSubjectParser() {
    }

    public static Parsed parse(String subject, LocalDate today, Function<String, Optional<UUID>> labelResolver) {
        String text = subject == null ? "" : subject.strip();
        String previous;
        do {
            previous = text;
            text = REPLY_PREFIX.matcher(text).replaceFirst("").strip();
        } while (!text.equals(previous));

        List<String> tokens = text.isEmpty() ? List.of() : Arrays.asList(text.split("\\s+"));
        List<String> kept = new ArrayList<>();
        Integer priority = null;
        LinkedHashSet<UUID> labels = new LinkedHashSet<>();
        EmailDateParser.Result due = null;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            var priorityMatch = PRIORITY.matcher(token);
            if (priorityMatch.matches()) {
                priority = Integer.parseInt(priorityMatch.group(1));
                continue;
            }
            if (token.length() > 1 && token.startsWith("@")) {
                Optional<UUID> label = labelResolver.apply(token.substring(1));
                if (label.isPresent()) {
                    labels.add(label.get());
                    continue;
                }
            }
            if (token.equalsIgnoreCase("date")) {
                int end = i + 1;
                while (end < tokens.size() && !SYNTAX.matcher(tokens.get(end)).matches()) {
                    end++;
                }
                int consumed = 0;
                for (int k = end - (i + 1); k >= 1; k--) {
                    Optional<EmailDateParser.Result> result = EmailDateParser.parse(tokens.subList(i + 1, i + 1 + k),
                            today);
                    if (result.isPresent()) {
                        due = result.get();
                        consumed = k;
                        break;
                    }
                }
                if (consumed > 0) {
                    i += consumed;
                    continue;
                }
            }
            kept.add(token);
        }
        String content = truncate(String.join(" ", kept), MAX_CONTENT);
        LocalDate dueDate = due == null || due.time() != null ? null : due.date();
        LocalDateTime dueDatetime = due == null || due.time() == null ? null : due.date().atTime(due.time());
        return new Parsed(content, priority, List.copyOf(labels), dueDate, dueDatetime);
    }

    /** Cắt tối đa max ký tự (UTF-16), không cắt đôi cặp surrogate. */
    public static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }
}
