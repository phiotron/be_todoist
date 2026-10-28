package com.taskly.dto.email;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Thư đã parse, do adapter của dịch vụ nhận thư (Mailgun, SendGrid, Postmark, Cloudflare Email Workers…) gửi tới webhook
 * inbound (M13a) — định dạng TRUNG LẬP nhà cung cấp. {@code to} nhận một chuỗi (có thể nhiều địa chỉ, dạng
 * {@code Tên <a@b>}, cách nhau bởi dấu phẩy) hoặc một mảng chuỗi. {@code attachments} và mọi field lạ bị bỏ qua.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundEmailRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 998,
                description = "Header Message-ID nguyên văn — chống xử lý lại khi dịch vụ gửi lại webhook")
        String messageId,
        @Schema(description = "Người gửi (không dùng để xác thực; địa chỉ nhận thư là bí mật)")
        String from,
        @Schema(description = "Người nhận (To / Cc / envelope) — chuỗi hoặc mảng chuỗi")
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<String> to,
        @Schema(description = "Tiêu đề → tên task (cú pháp date …, @nhãn, p1–p3, +người)")
        String subject,
        @Schema(description = "Nội dung dạng chữ thuần → mô tả task (M8b: comment)")
        String text) {
}
