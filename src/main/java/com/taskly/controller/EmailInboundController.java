package com.taskly.controller;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.config.EmailInboxProperties;
import com.taskly.dto.email.InboundEmailRequest;
import com.taskly.dto.email.InboundEmailResponse;
import com.taskly.security.EmailInboundRateLimiter;
import com.taskly.security.InboundSignatureVerifier;
import com.taskly.service.EmailInboundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Webhook inbound email (M13a) — PUBLIC (không JWT), xác thực bằng chữ ký HMAC (InboundSignatureVerifier). Thứ tự:
 * giới hạn theo IP → kích thước body → chữ ký trên body THÔ → parse JSON → messageId → xử lý. Đọc body dạng byte để
 * chữ ký tính đúng trên byte nhà cung cấp gửi.
 */
@RestController
@RequestMapping("/api/v1/integrations/email")
public class EmailInboundController {

    static final int MAX_MESSAGE_ID = 998;

    private final EmailInboundService inboundService;
    private final InboundSignatureVerifier verifier;
    private final EmailInboundRateLimiter rateLimiter;
    private final EmailInboxProperties properties;
    private final ObjectMapper objectMapper;

    public EmailInboundController(EmailInboundService inboundService, InboundSignatureVerifier verifier,
                                  EmailInboundRateLimiter rateLimiter, EmailInboxProperties properties,
                                  ObjectMapper objectMapper) {
        this.inboundService = inboundService;
        this.verifier = verifier;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/inbound", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Nhận thư đã parse từ dịch vụ inbound (ký HMAC) → tạo task / thêm vào task",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = InboundEmailRequest.class))))
    @Parameter(in = ParameterIn.HEADER, name = InboundSignatureVerifier.TIMESTAMP_HEADER, required = true,
            description = "Epoch giây lúc ký (lệch tối đa 5 phút)", schema = @Schema(type = "string"))
    @Parameter(in = ParameterIn.HEADER, name = InboundSignatureVerifier.SIGNATURE_HEADER, required = true,
            description = "v1=<hex HMAC-SHA256(secret, timestamp + \".\" + body)>", schema = @Schema(type = "string"))
    public InboundEmailResponse inbound(HttpServletRequest request,
                                        @RequestHeader(value = InboundSignatureVerifier.TIMESTAMP_HEADER,
                                                required = false) String timestamp,
                                        @RequestHeader(value = InboundSignatureVerifier.SIGNATURE_HEADER,
                                                required = false) String signature,
                                        @RequestBody(required = false) byte[] body) {
        rateLimiter.checkIp(request.getRemoteAddr());
        byte[] raw = body == null ? new byte[0] : body;
        if (raw.length > properties.maxBodyBytes()) {
            throw ApiException.badRequest(ErrorMessage.EMAIL_PAYLOAD_INVALID);
        }
        verifier.verify(timestamp, signature, raw);
        InboundEmailRequest mail;
        try {
            mail = objectMapper.readValue(raw, InboundEmailRequest.class);
        } catch (JacksonException e) {
            throw ApiException.badRequest(ErrorMessage.EMAIL_PAYLOAD_INVALID);
        }
        if (mail == null || mail.messageId() == null || mail.messageId().isBlank()
                || mail.messageId().length() > MAX_MESSAGE_ID) {
            throw ApiException.badRequest(ErrorMessage.EMAIL_PAYLOAD_INVALID);
        }
        return inboundService.receive(mail);
    }
}
