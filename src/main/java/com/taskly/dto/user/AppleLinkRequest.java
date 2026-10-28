package com.taskly.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** POST me/identities/apple (M13b) — gửi đúng MỘT trong hai: idToken (Cài đặt) hoặc linkToken (sau 409 link-required). */
@Schema(description = "Gửi đúng một trong hai: idToken hoặc linkToken")
public record AppleLinkRequest(
        @Size(max = 4096, message = "{validation.identity.token.too-long}")
        String idToken,

        @Size(max = 4096, message = "{validation.identity.token.too-long}")
        String linkToken) {
}
