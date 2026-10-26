package com.taskly.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Gửi đúng MỘT trong hai (kiểm ở service, sai → 400):
 * - idToken: liên kết từ Cài đặt → "Liên kết Google";
 * - linkToken: nhận từ 409 link-required của auth/google, sau khi đã đăng nhập bằng mật khẩu.
 */
@Schema(description = "Gửi đúng một trong hai: idToken hoặc linkToken")
public record GoogleLinkRequest(
        @Size(max = 4096, message = "idToken quá dài")
        String idToken,

        @Size(max = 4096, message = "linkToken quá dài")
        String linkToken) {
}
