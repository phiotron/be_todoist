package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemTexts;
import com.taskly.dto.display.PutViewOptionsRequest;
import com.taskly.dto.display.ViewOptionsResponse;
import com.taskly.entity.ViewOption;
import com.taskly.repository.ViewOptionRepository;
import com.taskly.service.display.DisplayOptions;
import com.taskly.service.display.DisplayRules;
import com.taskly.service.display.ViewKey;
import com.taskly.service.display.ViewKind;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Display đã lưu theo tài khoản (spec 12.7). Chủ bảng view_options: tự xoá Display của dự án / label / filter khi
 * chúng bị xoá bằng cách nghe ProjectsDeletedEvent (M1), LabelDeletedEvent, FilterDeletedEvent (đồng bộ, cùng
 * transaction với lệnh xoá — spec 8.2 quy tắc 8). Không có trigger DB.
 */
@Service
@Transactional
public class ViewOptionsService {

    private final ViewOptionRepository viewOptionRepository;
    private final ProjectService projectService;
    private final LabelService labelService;
    private final FilterService filterService;

    public ViewOptionsService(ViewOptionRepository viewOptionRepository, ProjectService projectService,
                              LabelService labelService, FilterService filterService) {
        this.viewOptionRepository = viewOptionRepository;
        this.projectService = projectService;
        this.labelService = labelService;
        this.filterService = filterService;
    }

    @Transactional(readOnly = true)
    public List<ViewOptionsResponse> list(UUID userId) {
        return viewOptionRepository.findByUserIdOrderByViewKeyAsc(userId).stream()
                .map(ViewOptionsService::toResponse).toList();
    }

    public ViewOptionsResponse put(UUID userId, String rawViewKey, PutViewOptionsRequest request) {
        ViewKey key = requireReadable(userId, rawViewKey);
        DisplayRules.validate(key.kind(), new DisplayOptions(request.sortBy(), request.sortDirection(),
                request.groupBy(), request.filterPriority(), request.filterLabelId(), request.filterDue()));
        boolean showCompleted = Boolean.TRUE.equals(request.showCompleted());
        if (showCompleted && !DisplayRules.allowsShowCompleted(key.kind())) {
            throw ApiException.badRequest(ProblemTexts.DISPLAY_INVALID);
        }
        if (request.filterLabelId() != null) {
            labelService.requireOwned(userId, request.filterLabelId());
        }
        ViewOption option = viewOptionRepository.findByUserIdAndViewKey(userId, key.format())
                .orElseGet(() -> new ViewOption(userId, key.format()));
        option.apply(request.sortBy(), request.sortDirection(), request.groupBy(), request.filterPriority(),
                request.filterLabelId(), request.filterDue(), showCompleted);
        viewOptionRepository.save(option);
        return toResponse(option);
    }

    public void reset(UUID userId, String rawViewKey) {
        ViewKey key = requireReadable(userId, rawViewKey);
        viewOptionRepository.deleteView(userId, key.format());
    }

    // ---- Dọn Display khi xoá dự án / label / filter (spec 5.7, 8.2 quy tắc 8): sự kiện đồng bộ, cùng transaction ----

    /** M1 phát khi xoá dự án (gồm mọi dự án con bị cascade) → xoá `project:<id>` của MỌI user (M8: dự án chung). */
    @EventListener
    public void onProjectsDeleted(ProjectsDeletedEvent event) {
        deleteKeys(event.projectIds().stream().map(id -> new ViewKey(ViewKind.PROJECT, id).format()).toList());
    }

    @EventListener
    public void onLabelDeleted(LabelDeletedEvent event) {
        deleteKeys(List.of(new ViewKey(ViewKind.LABEL, event.labelId()).format()));
    }

    @EventListener
    public void onFilterDeleted(FilterDeletedEvent event) {
        deleteKeys(List.of(new ViewKey(ViewKind.FILTER, event.filterId()).format()));
    }

    private void deleteKeys(List<String> viewKeys) {
        if (!viewKeys.isEmpty()) {
            viewOptionRepository.deleteViews(viewKeys);
        }
    }

    /** Khoá sai dạng → 400; dự án không đọc được / label, filter không phải của mình → 404. */
    private ViewKey requireReadable(UUID userId, String rawViewKey) {
        ViewKey key = ViewKey.parse(rawViewKey);
        switch (key.kind()) {
            case PROJECT -> projectService.requireReadable(userId, key.id());
            case LABEL -> labelService.requireOwned(userId, key.id());
            case FILTER -> filterService.requireOwned(userId, key.id());
            default -> {
                // TODAY, UPCOMING: không có id
            }
        }
        return key;
    }

    private static ViewOptionsResponse toResponse(ViewOption option) {
        return new ViewOptionsResponse(option.getViewKey(), option.getSortBy(), option.getSortDirection(),
                option.getGroupBy(), option.getFilterPriority(), option.getFilterLabelId(), option.getFilterDue(),
                option.isShowCompleted());
    }
}
