package com.taskly.service.sync;

import com.taskly.dto.section.SectionResponse;
import com.taskly.entity.Section;
import com.taskly.mapper.SectionMapper;
import com.taskly.repository.SectionRepository;
import com.taskly.security.AccessPolicy;
import com.taskly.security.ReadScope;
import com.taskly.service.FilterService;
import com.taskly.service.LabelService;
import com.taskly.service.ProjectService;
import com.taskly.service.TaskViewService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Loại thực thể M1–M2 trong "changes" của POST sync: dựng response bằng chính service / mapper của API REST (cùng hình
 * dạng, cùng lọc quyền). Mốc sau thêm loại mới bằng bean SyncEntitySource riêng (plan M9 mục 8.3), không sửa file này.
 */
@Configuration(proxyBeanMethods = false)
public class CoreSyncSources {

    /** Số id mỗi câu IN (xa dưới giới hạn 65 535 tham số của PostgreSQL). */
    static final int CHUNK = 500;

    @Bean
    SyncEntitySource projectSyncSource(ProjectService projectService) {
        return new Source("PROJECT", "projects",
                projectService::getResponses,
                userId -> {
                    List<Object> all = new ArrayList<>(projectService.list(userId, false));
                    all.addAll(projectService.list(userId, true));
                    return all;
                });
    }

    @Bean
    SyncEntitySource sectionSyncSource(SectionRepository sectionRepository, AccessPolicy accessPolicy,
                                       JdbcTemplate jdbcTemplate) {
        Function<List<UUID>, List<?>> responses = ids -> sectionResponses(sectionRepository, ids);
        return new Source("SECTION", "sections",
                (userId, ids) -> responses.apply(readableSectionIds(jdbcTemplate, accessPolicy.listScope(userId), ids)),
                userId -> responses.apply(readableSectionIds(jdbcTemplate, accessPolicy.listScope(userId), null)));
    }

    @Bean
    SyncEntitySource taskSyncSource(TaskViewService taskViewService, AccessPolicy accessPolicy, JdbcTemplate jdbcTemplate) {
        return new Source("TASK", "tasks",
                (userId, ids) -> chunked(ids, chunk -> taskViewService.getResponsesInOrder(userId, chunk)),
                userId -> {
                    ReadScope scope = accessPolicy.listScope(userId); // M8a: theo dự án đã tham gia
                    if (scope.isEmpty()) {
                        return List.of();
                    }
                    List<UUID> ids = jdbcTemplate.query(con -> {
                        var ps = con.prepareStatement("select id from tasks where workspace_id = any(?) "
                                + "and project_id = any(?) and completed_at is null order by project_id, sort_order, id");
                        ps.setArray(1, con.createArrayOf("uuid", scope.workspaceIds().toArray()));
                        ps.setArray(2, con.createArrayOf("uuid", scope.projectIds().toArray()));
                        return ps;
                    }, (rs, i) -> rs.getObject(1, UUID.class));
                    return chunked(ids, chunk -> taskViewService.getResponsesInOrder(userId, chunk));
                });
    }

    @Bean
    SyncEntitySource labelSyncSource(LabelService labelService) {
        return new Source("LABEL", "labels",
                (userId, ids) -> {
                    Set<UUID> wanted = new HashSet<>(ids);
                    return labelService.list(userId).stream().filter(l -> wanted.contains(l.id())).toList();
                },
                labelService::list);
    }

    @Bean
    SyncEntitySource filterSyncSource(FilterService filterService) {
        return new Source("FILTER", "filters",
                (userId, ids) -> {
                    Set<UUID> wanted = new HashSet<>(ids);
                    return filterService.list(userId).stream().filter(f -> wanted.contains(f.id())).toList();
                },
                filterService::list);
    }

    // ---- hỗ trợ ----

    interface Loader {
        List<?> load(UUID userId, List<UUID> ids);
    }

    record Source(String entityType, String changesKey, Loader loader, Function<UUID, List<?>> fullLoader)
            implements SyncEntitySource {

        @Override
        public List<?> load(UUID userId, List<UUID> ids) {
            return ids.isEmpty() ? List.of() : loader.load(userId, ids);
        }

        @Override
        public List<?> full(UUID userId) {
            return fullLoader.apply(userId);
        }
    }

    static <T> List<T> chunked(List<UUID> ids, Function<List<UUID>, List<T>> loader) {
        List<T> out = new ArrayList<>(ids.size());
        for (int from = 0; from < ids.size(); from += CHUNK) {
            out.addAll(loader.apply(ids.subList(from, Math.min(ids.size(), from + CHUNK))));
        }
        return out;
    }

    /** Section của dự án đọc được (M8a: ReadScope); ids null = tất cả (full sync), theo dự án rồi sort_order. */
    private static List<UUID> readableSectionIds(JdbcTemplate jdbcTemplate, ReadScope scope, List<UUID> ids) {
        if (scope.isEmpty() || (ids != null && ids.isEmpty())) {
            return List.of();
        }
        return jdbcTemplate.query(con -> {
            var ps = con.prepareStatement("select s.id from sections s join projects p on p.id = s.project_id "
                    + "where p.workspace_id = any(?) and p.id = any(?)" + (ids == null ? "" : " and s.id = any(?)")
                    + " order by s.project_id, s.sort_order, s.id");
            ps.setArray(1, con.createArrayOf("uuid", scope.workspaceIds().toArray()));
            ps.setArray(2, con.createArrayOf("uuid", scope.projectIds().toArray()));
            if (ids != null) {
                ps.setArray(3, con.createArrayOf("uuid", ids.toArray()));
            }
            return ps;
        }, (rs, i) -> rs.getObject(1, UUID.class));
    }

    private static List<SectionResponse> sectionResponses(SectionRepository repository, List<UUID> ids) {
        return chunked(ids, chunk -> {
            Map<UUID, Section> byId = repository.findAllById(chunk).stream()
                    .collect(Collectors.toMap(Section::getId, s -> s));
            List<Section> sections = chunk.stream().filter(byId::containsKey).map(byId::get).toList();
            if (sections.isEmpty()) {
                return List.of();
            }
            Map<UUID, Long> counts = repository.countOpenTasks(sections.stream().map(Section::getId).toList()).stream()
                    .collect(Collectors.toMap(SectionRepository.SectionTaskCount::getSectionId,
                            SectionRepository.SectionTaskCount::getTaskCount));
            return sections.stream()
                    .map(s -> SectionMapper.toResponse(s, counts.getOrDefault(s.getId(), 0L)))
                    .toList();
        });
    }
}
