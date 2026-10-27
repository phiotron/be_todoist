package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.validation.SettingsTargets.PinnedTarget;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mục ghim (project / label / filter) có đọc được bởi user không — dùng khi lưu startPage / navTabs (spec 12.1.1).
 * - PROJECT: ProjectService.requireReadable của M1 (không thấy / của người khác → 404 → "không đọc được").
 * - LABEL / FILTER: false cho tới khi gộp M2 (nối LabelService / FilterService.isReadable của M2).
 * Chạy trong transaction RIÊNG chỉ-đọc (TransactionTemplate, bắt lỗi Ở NGOÀI nó): ApiException ném qua ranh giới @Transactional của ProjectService đánh dấu
 * transaction ngoài rollback-only dù đã bắt lại → commit của SettingsService.update lỗi.
 */
@Service
public class PinnedTargetChecker {

    /** Cờ mốc: SEARCH / FILTERS_LABELS / label / filter chỉ khả dụng khi M2 đã gộp (spec 12.1.1). */
    static final boolean M2_MERGED = false;

    private final ProjectService projectService;
    private final TransactionTemplate readOnlyNewTx;

    public PinnedTargetChecker(ProjectService projectService, PlatformTransactionManager transactionManager) {
        this.projectService = projectService;
        this.readOnlyNewTx = new TransactionTemplate(transactionManager);
        this.readOnlyNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnlyNewTx.setReadOnly(true);
    }

    /** Tab cố định thuộc M2 (SEARCH, FILTERS_LABELS) chỉ khả dụng sau khi M2 gộp. Tab khác luôn khả dụng. */
    public boolean isTabAvailable(String tab) {
        return M2_MERGED || !("SEARCH".equals(tab) || "FILTERS_LABELS".equals(tab));
    }

    public boolean isReadable(UUID userId, PinnedTarget target) {
        return switch (target.kind()) {
            case PROJECT -> projectReadable(userId, target.id());
            case LABEL, FILTER -> false; // M2: nối LabelService / FilterService.isReadable khi gộp
        };
    }

    private boolean projectReadable(UUID userId, UUID projectId) {
        try {
            readOnlyNewTx.executeWithoutResult(status -> projectService.requireReadable(userId, projectId));
            return true;
        } catch (ApiException ex) {
            if (ex.getStatusCode().value() == 404) {
                return false;
            }
            throw ex;
        }
    }
}
