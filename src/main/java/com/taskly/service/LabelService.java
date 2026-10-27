package com.taskly.service;

import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;
import com.taskly.dto.common.Created;
import com.taskly.dto.label.CreateLabelRequest;
import com.taskly.dto.label.LabelResponse;
import com.taskly.dto.label.UpdateLabelRequest;
import com.taskly.entity.FavoriteTargetType;
import com.taskly.entity.Label;
import com.taskly.entity.LimitKind;
import com.taskly.entity.ProjectColor;
import com.taskly.mapper.LabelMapper;
import com.taskly.repository.LabelRepository;
import java.util.Collection;
import java.util.HashSet;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sở hữu bảng labels (label cá nhân — thuộc user, không phụ thuộc workspace, spec 11.1). Ghi sau thắng.
 * Xoá label: task_labels cascade (FK), filter_label_id → NULL (FK), yêu thích xoá ở đây, Display `label:<id>` do
 * ViewOptionsService xoá khi nghe LabelDeletedEvent (đồng bộ, cùng transaction — spec 8.2 quy tắc 8, không trigger).
 * Tạo label: khoá hàng users TRƯỚC khi tra id / đếm / insert (spec 8.2 quy tắc 9) và nhận id do client sinh (spec 12.11).
 */
@Service
@Transactional
public class LabelService {

    private final LabelRepository labelRepository;
    private final FavoriteService favoriteService;
    private final UsageLimits usageLimits;
    private final SortOrderService sortOrderService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public LabelService(LabelRepository labelRepository, FavoriteService favoriteService, UsageLimits usageLimits,
                        SortOrderService sortOrderService, ApplicationEventPublisher events,
                        Clock clock) {
        this.labelRepository = labelRepository;
        this.favoriteService = favoriteService;
        this.usageLimits = usageLimits;
        this.sortOrderService = sortOrderService;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<LabelResponse> list(UUID userId) {
        List<Label> labels = labelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
        Set<UUID> favorites = favoriteService.favoriteIds(userId, FavoriteTargetType.LABEL,
                labels.stream().map(Label::getId).toList());
        return labels.stream().map(label -> LabelMapper.toResponse(label, favorites.contains(label.getId()))).toList();
    }

    /**
     * Tạo label (spec 12.7, 12.11). Thứ tự: kiểm tên (400) → khoá hàng users → `id` client (cùng nội dung → trả bản cũ,
     * KHÔNG đếm lại giới hạn; khác / của người khác → 409 id-conflict) → giới hạn 500 (422) → trùng tên (409) → insert.
     */
    public Created<LabelResponse> create(UUID userId, CreateLabelRequest request) {
        String name = LabelNames.normalize(request.name());
        ProjectColor color = request.color() == null ? ProjectColor.charcoal : request.color();
        usageLimits.lockUser(userId); // tuần tự hoá mọi lần tạo label của user: chống đua giới hạn và đua cùng id
        if (request.id() != null) {
            Optional<Label> existing = labelRepository.findById(request.id());
            if (existing.isPresent()) {
                Label label = existing.get();
                if (label.getUserId().equals(userId) && label.getName().equals(name) && label.getColor() == color) {
                    return Created.replay(toResponse(userId, label));
                }
                throw idConflict();
            }
        }
        usageLimits.check(LimitKind.LABELS, labelRepository.countByUserId(userId), 1);
        if (labelRepository.existsWithName(userId, name)) {
            throw nameTaken();
        }
        long sortOrder = sortOrderService.placeLast(items(labelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId)))
                .sortOrder();
        Label label = new Label(request.id() == null ? UUID.randomUUID() : request.id(), userId, name, color,
                sortOrder);
        try {
            labelRepository.saveAndFlush(label);
        } catch (DataIntegrityViolationException ex) {
            // Đã khoá user + kiểm tên / id ở trên → chỉ còn va chạm khoá chính với id của NGƯỜI KHÁC vừa commit.
            throw request.id() != null ? idConflict() : nameTaken();
        }
        return Created.fresh(LabelMapper.toResponse(label, false));
    }

    public LabelResponse update(UUID userId, UUID labelId, UpdateLabelRequest request) {
        Label label = requireOwned(userId, labelId);
        if (request.name() != null) {
            String name = LabelNames.normalize(request.name());
            if (!name.equals(label.getName())) {
                if (labelRepository.existsOtherWithName(userId, name, labelId)) {
                    throw nameTaken();
                }
                label.setName(name);
            }
        }
        if (request.color() != null && request.color() != label.getColor()) {
            label.setColor(request.color());
        }
        saveCheckingName(label);
        return toResponse(userId, label);
    }

    public void delete(UUID userId, UUID labelId) {
        Label label = requireOwned(userId, labelId);
        favoriteService.remove(userId, FavoriteTargetType.LABEL, labelId);
        labelRepository.delete(label);
        events.publishEvent(new LabelDeletedEvent(userId, labelId)); // ViewOptionsService xoá Display `label:<id>` (Task 17)
    }

    public LabelResponse setFavorite(UUID userId, UUID labelId, boolean favorite) {
        Label label = requireOwned(userId, labelId);
        if (favorite) {
            favoriteService.add(userId, FavoriteTargetType.LABEL, labelId);
        } else {
            favoriteService.remove(userId, FavoriteTargetType.LABEL, labelId);
        }
        return LabelMapper.toResponse(label, favorite);
    }

    /** Label của chính user; không có / của người khác → 404. */
    @Transactional(readOnly = true)
    public Label requireOwned(UUID userId, UUID labelId) {
        return labelRepository.findByIdAndUserId(labelId, userId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.LABEL_NOT_FOUND));
    }

    /**
     * Móc cho M3 (`PinnedTargetChecker`: ghim `label:<id>` trong navTabs / startPage): label có đọc được không.
     * Không ném lỗi, không tải entity. Hiện = label của chính user; M8 (label dùng chung) mở rộng tại đây.
     */
    @Transactional(readOnly = true)
    public boolean isReadable(UUID userId, UUID labelId) {
        return labelRepository.existsByIdAndUserId(labelId, userId);
    }

    /** Mọi id phải là label của user (bỏ trùng trước khi đếm); thiếu một → 404. */
    @Transactional(readOnly = true)
    public void requireAllOwned(UUID userId, Collection<UUID> labelIds) {
        Set<UUID> wanted = new HashSet<>(labelIds);
        if (wanted.isEmpty()) {
            return;
        }
        if (labelRepository.findByUserIdAndIdIn(userId, wanted).size() != wanted.size()) {
            throw ApiException.notFound(ErrorMessage.LABEL_NOT_FOUND);
        }
    }

    private LabelResponse toResponse(UUID userId, Label label) {
        boolean favorite = favoriteService.favoriteIds(userId, FavoriteTargetType.LABEL, List.of(label.getId()))
                .contains(label.getId());
        return LabelMapper.toResponse(label, favorite);
    }

    /** Đua tạo / đổi tên trùng: chỉ mục unique (user_id, lower(name)) ở DB chặn → cùng 409. */
    private void saveCheckingName(Label label) {
        try {
            labelRepository.saveAndFlush(label);
        } catch (DataIntegrityViolationException ex) {
            throw nameTaken();
        }
    }

    /** POST reorder type LABEL: vùng chứa = mọi label của user. Native UPDATE: không qua entity, không đụng tasks. */
    public long reorder(UUID userId, UUID labelId, UUID afterId) {
        requireOwned(userId, labelId);
        List<Label> siblings = labelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .filter(other -> !other.getId().equals(labelId)).toList();
        SortOrderService.Placement placement = sortOrderService.placeAfter(items(siblings), afterId);
        Instant now = clock.instant();
        if (placement.renumbers()) {
            siblings.forEach(other -> {
                Long renumbered = placement.renumbered().get(other.getId());
                if (renumbered != null) {
                    labelRepository.updateSortOrder(other.getId(), renumbered, now);
                }
            });
        }
        labelRepository.updateSortOrder(labelId, placement.sortOrder(), now);
        return placement.sortOrder();
    }

    private static List<SortOrderService.Item> items(List<Label> labels) {
        return labels.stream().map(label -> new SortOrderService.Item(label.getId(), label.getSortOrder())).toList();
    }

    private static ApiException nameTaken() {
        return ApiException.conflict(ErrorMessage.LABEL_NAME_TAKEN).withCode(ProblemCodes.LABEL_NAME_TAKEN);
    }

    /** `id` client trùng id có sẵn nhưng khác nội dung / của người khác (spec 12.11) — không lộ gì thêm. */
    private static ApiException idConflict() {
        return ApiException.conflict(ErrorMessage.ID_CONFLICT).withCode(ProblemCodes.ID_CONFLICT);
    }
}
