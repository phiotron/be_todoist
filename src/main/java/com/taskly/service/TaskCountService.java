package com.taskly.service;

import com.taskly.dto.filter.FilterTaskCountResponse;
import com.taskly.dto.label.LabelTaskCountResponse;
import com.taskly.entity.Filter;
import com.taskly.entity.Label;
import com.taskly.repository.FilterRepository;
import com.taskly.repository.LabelRepository;
import com.taskly.repository.TaskCountRepository;
import com.taskly.security.AccessPolicy;
import com.taskly.security.ReadScope;
import com.taskly.service.filterquery.FilterNode;
import com.taskly.service.filterquery.FilterQueryParser;
import com.taskly.service.filterquery.FilterSyntaxException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cột số việc của màn Bộ lọc &amp; Nhãn (FL). Phạm vi = AccessPolicy.listScope như danh sách label / filter (M8a: người
 * cộng tác chỉ đếm việc của dự án được chia sẻ). Nhãn: một câu GROUP BY. Filter: các phần (',') gộp bằng OR → một việc
 * khớp hai phần chỉ đếm một lần; query lưu mà nay không parse được (luật cú pháp đổi) thì bỏ khỏi kết quả.
 */
@Service
@Transactional(readOnly = true)
public class TaskCountService {

    private final LabelRepository labelRepository;
    private final FilterRepository filterRepository;
    private final TaskCountRepository taskCountRepository;
    private final AccessPolicy accessPolicy;
    private final UserService userService;
    private final FilterService filterService;
    private final Clock clock;

    public TaskCountService(LabelRepository labelRepository, FilterRepository filterRepository,
                            TaskCountRepository taskCountRepository, AccessPolicy accessPolicy,
                            UserService userService, FilterService filterService, Clock clock) {
        this.labelRepository = labelRepository;
        this.filterRepository = filterRepository;
        this.taskCountRepository = taskCountRepository;
        this.accessPolicy = accessPolicy;
        this.userService = userService;
        this.filterService = filterService;
        this.clock = clock;
    }

    /** Mỗi nhãn của user một mục, theo thứ tự nhãn; nhãn không có việc = 0. */
    public List<LabelTaskCountResponse> labelCounts(UUID userId) {
        List<Label> labels = labelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
        if (labels.isEmpty()) {
            return List.of();
        }
        ReadScope scope = accessPolicy.listScope(userId);
        Map<UUID, Long> counts = scope.isEmpty() ? Map.of() : taskCountRepository.countByLabel(userId, scope);
        return labels.stream()
                .map(label -> new LabelTaskCountResponse(label.getId(), counts.getOrDefault(label.getId(), 0L)))
                .toList();
    }

    /** Mỗi filter (query parse được) một mục, theo thứ tự filter. */
    public List<FilterTaskCountResponse> filterCounts(UUID userId) {
        Map<UUID, FilterNode> nodes = new LinkedHashMap<>();
        for (Filter filter : filterRepository.findByUserIdOrderBySortOrderAscIdAsc(userId)) {
            try {
                nodes.put(filter.getId(), FilterQueryParser.parseAll(filter.getQuery(), filterService.dateOrder(userId))
                        .stream().reduce(FilterNode.Or::new).orElseThrow());
            } catch (FilterSyntaxException ex) {
                // Query cũ không còn hợp lệ: không có số (màn filter sẽ báo lỗi cú pháp khi mở).
            }
        }
        if (nodes.isEmpty()) {
            return List.of();
        }
        ReadScope scope = accessPolicy.listScope(userId);
        Map<UUID, Long> counts;
        if (scope.isEmpty()) {
            counts = Map.of();
        } else {
            ZoneId zone = userService.timeZoneOf(userId);
            Clock local = clock.withZone(zone);
            counts = taskCountRepository.countByFilter(userId, scope, LocalDate.now(local), LocalDateTime.now(local),
                    zone.getId(), nodes);
        }
        return nodes.keySet().stream()
                .map(id -> new FilterTaskCountResponse(id, counts.getOrDefault(id, 0L)))
                .toList();
    }
}
