package com.taskly.service;

import com.taskly.common.web.CursorCodec;
import com.taskly.dto.common.Page;
import com.taskly.dto.filter.FilterResponse;
import com.taskly.dto.label.LabelResponse;
import com.taskly.dto.search.SearchObjectsResponse;
import com.taskly.dto.search.SearchQuery;
import com.taskly.dto.search.SectionSearchItem;
import com.taskly.repository.ObjectSearchRepository;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.taskly.dto.task.TaskResponse;
import com.taskly.repository.TaskSearchRepository;
import com.taskly.repository.listing.KeysetPager.KeyedId;
import com.taskly.repository.listing.SortKey;
import com.taskly.security.AccessPolicy;
import com.taskly.security.ReadScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tìm kiếm (spec 12.6): phạm vi từ AccessPolicy (BR-09), dựng TaskResponse bằng TaskViewService (M1). */
@Service
@Transactional(readOnly = true)
public class SearchService {

    private final TaskSearchRepository taskSearchRepository;
    private final AccessPolicy accessPolicy;
    private final TaskViewService taskViewService;

    public static final int MAX_OBJECTS = 20;

    private final ObjectSearchRepository objectSearchRepository;
    private final ProjectService projectService;
    private final LabelService labelService;
    private final FilterService filterService;
    /** M8b: comment trong search/objects. */
    private final CommentService commentService;

    public SearchService(TaskSearchRepository taskSearchRepository, AccessPolicy accessPolicy,
                         TaskViewService taskViewService, ObjectSearchRepository objectSearchRepository,
                         ProjectService projectService, LabelService labelService, FilterService filterService,
                         CommentService commentService) {
        this.taskSearchRepository = taskSearchRepository;
        this.accessPolicy = accessPolicy;
        this.taskViewService = taskViewService;
        this.objectSearchRepository = objectSearchRepository;
        this.projectService = projectService;
        this.labelService = labelService;
        this.filterService = filterService;
        this.commentService = commentService;
    }

    /** Dự án / section / label / filter khớp tên (spec 12.6): mỗi loại ≤ 20, label + filter chỉ của chính user. */
    public SearchObjectsResponse searchObjects(UUID userId, String q) {
        Optional<SearchQuery> parsed = SearchQuery.parse(q);
        if (parsed.isEmpty()) {
            return SearchObjectsResponse.empty();
        }
        SearchQuery query = parsed.get();
        ReadScope scope = accessPolicy.listScope(userId); // M8a: dự án đã tham gia
        List<UUID> projectIds = scope.isEmpty() ? List.of()
                : objectSearchRepository.projectIds(scope, query, MAX_OBJECTS);
        List<SectionSearchItem> sections = scope.isEmpty() ? List.of()
                : objectSearchRepository.sections(scope, query, MAX_OBJECTS);
        List<UUID> labelIds = objectSearchRepository.labelIds(userId, query, MAX_OBJECTS);
        List<UUID> filterIds = objectSearchRepository.filterIds(userId, query, MAX_OBJECTS);
        return new SearchObjectsResponse(
                projectService.getResponses(userId, projectIds),
                sections,
                inOrder(labelIds, labelIds.isEmpty() ? List.of() : labelService.list(userId), LabelResponse::id),
                inOrder(filterIds, filterIds.isEmpty() ? List.of() : filterService.list(userId), FilterResponse::id),
                commentService.search(scope, query, MAX_OBJECTS));
    }

    /** Giữ thứ tự của ids; DTO dựng bởi service chủ sở hữu (danh sách đầy đủ của user, bị chặn bởi giới hạn). */
    private static <T> List<T> inOrder(List<UUID> ids, List<T> all, Function<T, UUID> idOf) {
        Map<UUID, T> byId = all.stream().collect(Collectors.toMap(idOf, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    public Page<TaskResponse> searchTasks(UUID userId, String q, boolean completed, String cursor, Integer limit) {
        int pageSize = CursorCodec.limit(limit);
        Optional<SearchQuery> parsed = SearchQuery.parse(q);
        if (parsed.isEmpty()) {
            return new Page<>(List.of(), null);
        }
        SearchQuery query = parsed.get();
        String kind = cursorKind(query, completed);
        List<SortKey> keys = taskSearchRepository.keys(query, completed);
        CursorCodec.Cursor after = CursorCodec.hasCursor(cursor) ? CursorCodec.decode(kind, cursor, keys.size()) : null;
        ReadScope scope = accessPolicy.listScope(userId); // M8a: dự án đã tham gia
        if (scope.isEmpty()) {
            return new Page<>(List.of(), null);
        }
        List<KeyedId> rows = taskSearchRepository.search(scope, query, completed, after, pageSize + 1);
        Page<KeyedId> page = Page.fromLookahead(rows, pageSize, row -> CursorCodec.encode(kind, row.keys()));
        List<TaskResponse> items = taskViewService.getResponsesInOrder(userId,
                page.items().stream().map(KeyedId::id).toList());
        return new Page<>(items, page.nextCursor());
    }

    /** Con trỏ gắn với chế độ + từ khoá: đổi từ khoá / chế độ mà dùng lại con trỏ cũ → 400 (cursor không dùng chéo). */
    static String cursorKind(SearchQuery query, boolean completed) {
        return "search:" + (completed ? "done" : "open") + ":" + digest(query.normalized());
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
