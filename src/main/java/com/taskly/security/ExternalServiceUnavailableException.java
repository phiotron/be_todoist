package com.taskly.security;

/** Không gọi được dịch vụ ngoài (JWKS của Apple, Graph API của Facebook, API lịch). Cause chỉ để ghi loại lỗi. */
public class ExternalServiceUnavailableException extends RuntimeException {

    public ExternalServiceUnavailableException(Throwable cause) {
        super("external service unavailable", cause);
    }
}
