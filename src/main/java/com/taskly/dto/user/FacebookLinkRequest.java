package com.taskly.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** POST me/identities/facebook (M13b) — gửi đúng MỘT trong hai: accessToken (Cài đặt) hoặc linkToken (sau 409). */
@Schema(description = "Gửi đúng một trong hai: accessToken hoặc linkToken")
public record FacebookLinkRequest(
        @Size(max = 4096, message = "{validation.identity.token.too-long}")
        String accessToken,

        @Size(max = 4096, message = "{validation.identity.token.too-long}")
        String linkToken) {
}
