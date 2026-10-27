package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;
import com.taskly.common.error.ProblemTexts;
import com.taskly.dto.common.Created;
import com.taskly.dto.filter.CreateFilterRequest;
import com.taskly.dto.filter.FilterResponse;
import com.taskly.dto.filter.UpdateFilterRequest;
import com.taskly.dto.filter.ValidateFilterResponse;
import com.taskly.entity.FavoriteTargetType;
import com.taskly.entity.Filter;
import com.taskly.entity.LimitKind;
import com.taskly.entity.ProjectColor;
import com.taskly.mapper.FilterMapper;
import com.taskly.repository.FilterRepository;
import com.taskly.service.filterquery.DateOrder;
import com.taskly.service.filterquery.FilterQueryParser;
import com.taskly.service.filterquery.FilterSyntaxException;
import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sở hữu bảng filters. Query lưu nguyên văn, kiểm bằng FilterQueryParser (mọi phần). Ghi sau thắng.
 * Xoá filter: yêu thích xoá ở đây, Display `filter:<id>` do ViewOptionsService xoá khi nghe FilterDeletedEvent
 * (đồng bộ, cùng transaction — spec 8.2 quy tắc 8, không trigger). Tạo filter: khoá hàng users trước khi tra id / đếm /
 * insert (spec 8.2 quy tắc 9) và nhận id do client sinh (spec 12.11).
 */
@Service
@Transactional
public class FilterService {

    public static final int MAX_NAME_LENGTH = 120;

    private final FilterRepository filterRepository;
    private final FavoriteService favoriteService;
    private final UsageLimits usageLimits;
    private final SortOrderService sortOrderService;
    private final ApplicationEventPublisher events;

    public FilterService(FilterRepository filterRepository, FavoriteService favoriteService, UsageLimits usageLimits,
                         SortOrderService sortOrderService, ApplicationEventPublisher events) {
        this.filterRepository = filterRepository;
        this.favoriteService = favoriteService;
        this.usageLimits = usageLimits;
        this.sortOrderService = sortOrderService;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<FilterResponse> list(UUID userId) {
        List<Filter> filters = filterRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
        Set<UUID> favorites = favoriteService.favoriteIds(userId, FavoriteTargetType.FILTER,
                filters.stream().map(Filter::getId).toList());
        return filters.stream().map(filter -> FilterMapper.toResponse(filter, favorites.contains(filter.getId())))
                .toList();
    }

    /**
     * Tạo filter (spec 12.7, 12.11). Thứ tự: kiểm tên / query (400) → khoá hàng users → `id` client (cùng name + query +
     * color → trả bản cũ, KHÔNG đếm lại giới hạn; khác / của người khác → 409 id-conflict) → giới hạn 150 (422) → insert.
     */
    public Created<FilterResponse> create(UUID userId, CreateFilterRequest request) {
        String name = normalizeName(request.name());
        String query = checkQuery(request.query(), dateOrder(userId));
        ProjectColor color = request.color() == null ? ProjectColor.charcoal : request.color();
        usageLimits.lockUser(userId); // tuần tự hoá mọi lần tạo filter của user: chống đua giới hạn và đua cùng id
        if (request.id() != null) {
            Optional<Filter> existing = filterRepository.findById(request.id());
            if (existing.isPresent()) {
                Filter filter = existing.get();
                if (filter.getUserId().equals(userId) && filter.getName().equals(name)
                        && filter.getQuery().equals(query) && filter.getColor() == color) {
                    return Created.replay(toResponse(userId, filter));
                }
                throw idConflict();
            }
        }
        usageLimits.check(LimitKind.FILTERS, filterRepository.countByUserId(userId), 1);
        long sortOrder = sortOrderService.placeLast(items(filterRepository.findByUserIdOrderBySortOrderAscIdAsc(userId)))
                .sortOrder();
        Filter filter = new Filter(request.id() == null ? UUID.randomUUID() : request.id(), userId, name, query, color,
                sortOrder);
        try {
            filterRepository.saveAndFlush(filter);
        } catch (DataIntegrityViolationException ex) {
            // Đã khoá user + kiểm id ở trên → chỉ còn va chạm khoá chính với id của NGƯỜI KHÁC vừa commit.
            throw idConflict();
        }
        return Created.fresh(FilterMapper.toResponse(filter, false));
    }

    public FilterResponse update(UUID userId, UUID filterId, UpdateFilterRequest request) {
        Filter filter = requireOwned(userId, filterId);
        if (request.name() != null) {
            String name = normalizeName(request.name());
            if (!name.equals(filter.getName())) {
                filter.setName(name);
            }
        }
        if (request.query() != null) {
            String query = checkQuery(request.query(), dateOrder(userId));
            if (!query.equals(filter.getQuery())) {
                filter.setQuery(query);
            }
        }
        if (request.color() != null && request.color() != filter.getColor()) {
            filter.setColor(request.color());
        }
        boolean favorite = favoriteService.favoriteIds(userId, FavoriteTargetType.FILTER, List.of(filterId))
                .contains(filterId);
        return FilterMapper.toResponse(filter, favorite);
    }

    public void delete(UUID userId, UUID filterId) {
        Filter filter = requireOwned(userId, filterId);
        favoriteService.remove(userId, FavoriteTargetType.FILTER, filterId);
        filterRepository.delete(filter);
        events.publishEvent(new FilterDeletedEvent(userId, filterId)); // ViewOptionsService xoá Display `filter:<id>` (Task 17)
    }

    public FilterResponse setFavorite(UUID userId, UUID filterId, boolean favorite) {
        Filter filter = requireOwned(userId, filterId);
        if (favorite) {
            favoriteService.add(userId, FavoriteTargetType.FILTER, filterId);
        } else {
            favoriteService.remove(userId, FavoriteTargetType.FILTER, filterId);
        }
        return FilterMapper.toResponse(filter, favorite);
    }

    /** filters/validate: rỗng / sai → 400 filter-syntax (vị trí trong cả query); dài quá → 400 FILTER_QUERY_SIZE. */
    @Transactional(readOnly = true)
    public ValidateFilterResponse validate(UUID userId, String query) {
        if (query.length() > FilterQueryParser.MAX_LENGTH) {
            throw ApiException.badRequest(ProblemTexts.FILTER_QUERY_SIZE);
        }
        try {
            FilterQueryParser.parseAll(query, dateOrder(userId));
            return new ValidateFilterResponse(FilterQueryParser.split(query).stream()
                    .map(FilterQueryParser.Part::text).toList());
        } catch (FilterSyntaxException ex) {
            throw ex.toApiException();
        }
    }

    @Transactional(readOnly = true)
    public Filter requireOwned(UUID userId, UUID filterId) {
        return filterRepository.findByIdAndUserId(filterId, userId)
                .orElseThrow(() -> ApiException.notFound(ProblemTexts.FILTER_NOT_FOUND));
    }

    /**
     * Móc cho M3 (`PinnedTargetChecker`: ghim `filter:<id>` trong navTabs / startPage): filter có đọc được không.
     * Không ném lỗi, không tải entity. Hiện = filter của chính user.
     */
    @Transactional(readOnly = true)
    public boolean isReadable(UUID userId, UUID filterId) {
        return filterRepository.existsByIdAndUserId(filterId, userId);
    }

    /** Thứ tự ngày khi đọc "dd/MM". M3 (users.date_format) thay bằng cài đặt của user. */
    DateOrder dateOrder(UUID userId) {
        return DateOrder.DMY;
    }

    private static String normalizeName(String raw) {
        if (raw == null) {
            throw ApiException.badRequest(ProblemTexts.FILTER_NAME_SIZE);
        }
        String name = Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > MAX_NAME_LENGTH) {
            throw ApiException.badRequest(ProblemTexts.FILTER_NAME_SIZE);
        }
        return name;
    }

    /** Query lưu nguyên văn: rỗng / > 1024 → FILTER_QUERY_SIZE; cú pháp sai ở bất kỳ phần nào → filter-syntax. */
    private static String checkQuery(String raw, DateOrder order) {
        if (raw == null || raw.isBlank() || raw.length() > FilterQueryParser.MAX_LENGTH) {
            throw ApiException.badRequest(ProblemTexts.FILTER_QUERY_SIZE);
        }
        try {
            FilterQueryParser.parseAll(raw, order);
            return raw;
        } catch (FilterSyntaxException ex) {
            throw ex.toApiException();
        }
    }

    private FilterResponse toResponse(UUID userId, Filter filter) {
        boolean favorite = favoriteService.favoriteIds(userId, FavoriteTargetType.FILTER, List.of(filter.getId()))
                .contains(filter.getId());
        return FilterMapper.toResponse(filter, favorite);
    }

    private static List<SortOrderService.Item> items(List<Filter> filters) {
        return filters.stream().map(filter -> new SortOrderService.Item(filter.getId(), filter.getSortOrder())).toList();
    }

    /** `id` client trùng id có sẵn nhưng khác nội dung / của người khác (spec 12.11) — không lộ gì thêm. */
    private static ApiException idConflict() {
        return ApiException.conflict(ProblemTexts.ID_CONFLICT).withCode(ProblemCodes.ID_CONFLICT);
    }
}

