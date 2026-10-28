package com.taskly.dto.sharing;

import com.taskly.common.EmailNormalizer;
import com.taskly.entity.WorkspaceRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * M8a: mời qua email (POST projects/{id}/invitations, POST workspaces/{id}/invitations). role chỉ dùng cho lời mời
 * workspace: ADMIN | MEMBER (mặc định MEMBER); lời mời dự án bỏ qua role.
 */
public record InviteRequest(
        @NotBlank(message = "{validation.email.required}")
        @Email(message = "{validation.email.invalid}")
        @Size(max = 254, message = "{validation.email.too-long}")
        String email,

        @Schema(description = "Chỉ lời mời workspace: ADMIN | MEMBER (mặc định MEMBER)")
        WorkspaceRole role) {

    public InviteRequest {
        email = EmailNormalizer.normalize(email);
    }
}
