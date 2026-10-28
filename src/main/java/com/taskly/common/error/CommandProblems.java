package com.taskly.common.error;

import java.net.URI;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/**
 * M9: lỗi của MỘT lệnh trong POST sync → ProblemDetail PHẲNG (Map) đã dịch, cùng status / code / detail như
 * GlobalExceptionHandler trả cho API REST tương ứng. Lỗi lệnh nằm trong body 200 nên ProblemDetailLocalizer không dịch —
 * ở đây tự dịch theo locale của request. errors[] của lỗi validate đến qua {@link FieldErrors}.
 */
@Component
public class CommandProblems {

    private static final Logger log = LoggerFactory.getLogger(CommandProblems.class);
    private static final URI BLANK_TYPE = URI.create("about:blank");

    /** Lỗi validate args của lệnh (message là KEY). Lớp gọi bọc lỗi riêng của mình về dạng này. */
    public interface FieldErrors {
        List<FieldErrorItem> errors();
    }

    private final MessageSource messageSource;

    public CommandProblems(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public Map<String, Object> toProblem(Throwable error, Locale locale) {
        ProblemDetail problem;
        if (error instanceof ApiException api) {
            problem = api.getBody();
        } else if (error instanceof FieldErrors fieldErrors) {
            problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ErrorMessage.VALIDATION_FAILED.key());
            problem.setProperty("errors", fieldErrors.errors());
        } else if (error instanceof JacksonException) {
            problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ErrorMessage.VALIDATION_FAILED.key());
        } else if (error instanceof OptimisticLockingFailureException
                || error instanceof PessimisticLockingFailureException) {
            problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ErrorMessage.CONCURRENT_UPDATE.key());
            problem.setProperty(ProblemCodes.PROPERTY, ProblemCodes.VERSION_CONFLICT);
        } else if (error instanceof DataIntegrityViolationException) {
            log.warn("Lệnh đồng bộ vi phạm ràng buộc dữ liệu: {}", error.getClass().getSimpleName());
            problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ErrorMessage.DATA_CONFLICT.key());
        } else {
            log.error("Lỗi không mong đợi khi chạy lệnh đồng bộ", error);
            problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ErrorMessage.INTERNAL_ERROR.key());
        }
        return flatten(problem, locale);
    }

    private Map<String, Object> flatten(ProblemDetail problem, Locale locale) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", (problem.getType() == null ? BLANK_TYPE : problem.getType()).toString());
        body.put("title", text(ProblemTexts.titleKey(problem.getStatus()), locale));
        body.put("status", problem.getStatus());
        if (problem.getDetail() != null) {
            body.put("detail", text(problem.getDetail(), locale));
        }
        Map<String, Object> properties = problem.getProperties();
        if (properties != null) {
            properties.forEach((name, value) -> body.put(name, localizeErrors(value, locale)));
        }
        return body;
    }

    private Object localizeErrors(Object value, Locale locale) {
        if (value instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(FieldErrorItem.class::isInstance)) {
            return list.stream().map(FieldErrorItem.class::cast)
                    .map(item -> new FieldErrorItem(item.field(), text(item.message(), locale)))
                    .sorted(Comparator.comparing(FieldErrorItem::field).thenComparing(FieldErrorItem::message))
                    .toList();
        }
        return value;
    }

    private String text(String keyOrText, Locale locale) {
        return messageSource.getMessage(keyOrText, null, keyOrText, locale);
    }
}
