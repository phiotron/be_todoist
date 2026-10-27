package com.taskly.security;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.repository.WorkspaceMemberRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Nơi DUY NHẤT quyết định quyền (spec mục 5.5, 8.2.3, 11.1).
 * Mức 1: thành viên workspace được đọc và ghi mọi thứ trong workspace; không phải thành viên → 404 (không lộ id
 * có tồn tại). M8 sẽ thêm vai trò ở checkWrite: thành viên thiếu vai trò → 403 (ApiException.forbidden).
 */
@Component
public class AccessPolicy {

    private final WorkspaceMemberRepository memberRepository;

    public AccessPolicy(WorkspaceMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    public boolean canRead(UUID userId, UUID workspaceId) {
        return memberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId);
    }

    public void checkRead(UUID userId, UUID workspaceId, ErrorMessage notFoundDetail) {
        if (!canRead(userId, workspaceId)) {
            throw ApiException.notFound(notFoundDetail);
        }
    }

    public void checkWrite(UUID userId, UUID workspaceId, ErrorMessage notFoundDetail) {
        checkRead(userId, workspaceId, notFoundDetail);
    }

    /** Phạm vi cho view / tìm kiếm / lọc: các workspace user là thành viên (BR-09). */
    public List<UUID> readableWorkspaceIds(UUID userId) {
        return memberRepository.findWorkspaceIdsByUserId(userId);
    }
}
