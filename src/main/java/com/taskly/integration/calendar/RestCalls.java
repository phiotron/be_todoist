package com.taskly.integration.calendar;

import com.taskly.security.ExternalServiceUnavailableException;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/** Ánh xạ lỗi HTTP chung cho các client lịch: 401 / 403 → CalendarAuthException; mạng / 5xx → dịch vụ không sẵn sàng. */
final class RestCalls {

    private RestCalls() {
    }

    static <T> T call(Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)
                    || ex.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN)) {
                throw new CalendarAuthException();
            }
            throw new ExternalServiceUnavailableException(ex);
        } catch (RestClientException ex) {
            throw new ExternalServiceUnavailableException(ex);
        }
    }

    /** Như call nhưng 404 / 410 (tài nguyên đã mất) trả notFoundValue. */
    static <T> T callOrMissing(Supplier<T> call, T notFoundValue) {
        try {
            return call(call);
        } catch (ExternalServiceUnavailableException ex) {
            if (ex.getCause() instanceof HttpClientErrorException http
                    && (http.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)
                    || http.getStatusCode().isSameCodeAs(HttpStatus.GONE))) {
                return notFoundValue;
            }
            throw ex;
        }
    }

    /** Lỗi khi đổi code / làm mới token: 400 (invalid_grant) = quyền đã bị thu hồi. */
    static Map<String, Object> token(Supplier<Map<String, Object>> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException ex) {
            throw new CalendarAuthException();
        } catch (RestClientException ex) {
            throw new ExternalServiceUnavailableException(ex);
        }
    }

    static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
