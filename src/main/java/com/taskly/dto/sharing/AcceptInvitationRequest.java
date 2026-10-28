package com.taskly.dto.sharing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** M8a: POST invitations/accept — token của thư mời hoặc của link mời (dán ở màn Tham gia / deep link). */
public record AcceptInvitationRequest(
        @NotBlank(message = "{validation.invalid}")
        @Size(max = 128, message = "{validation.invalid}")
        String token) {

    public AcceptInvitationRequest {
        token = token == null ? null : token.strip();
    }
}
