package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.section.MoveSectionRequest;
import com.taskly.dto.section.SectionResponse;
import com.taskly.entity.LimitKind;
import com.taskly.entity.Project;
import com.taskly.entity.Section;
import com.taskly.mapper.SectionMapper;
import com.taskly.repository.SectionRepository;
import com.taskly.repository.TaskRepository;
import com.taskly.service.content.ProjectContent.NewTask;
import com.taskly.service.content.ProjectContentReader;
import com.taskly.service.content.ProjectContentWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M11 F2 (nhân bản section) + F3 (Move section sang dự án khác). Reorder section trong dự án đã có ở M1 (ReorderService).
 * Nguồn: dự án lưu trữ → 409 project-archived, section lưu trữ → 409 section-archived. Khoá: dự án (P) — move khoá nguồn +
 * đích theo id tăng dần — rồi đọc lại và mới đếm giới hạn của dự án nhận section.
 */
@Service
@Transactional
public class SectionTransferService {

    @PersistenceContext
    private EntityManager entityManager;

    private final ProjectService projectService;
    private final SectionService sectionService;
    private final SectionRepository sectionRepository;
    private final TaskRepository taskRepository;
    private final SortOrderService sortOrderService;
    private final UsageLimits usageLimits;
    private final ProjectContentReader contentReader;
    private final ProjectContentWriter contentWriter;
    private final Clock clock;

    public SectionTransferService(ProjectService projectService, SectionService sectionService,
                                  SectionRepository sectionRepository, TaskRepository taskRepository,
                                  SortOrderService sortOrderService, UsageLimits usageLimits,
                                  ProjectContentReader contentReader, ProjectContentWriter contentWriter, Clock clock) {
        this.projectService = projectService;
        this.sectionService = sectionService;
        this.sectionRepository = sectionRepository;
        this.taskRepository = taskRepository;
        this.sortOrderService = sortOrderService;
        this.usageLimits = usageLimits;
        this.contentReader = contentReader;
        this.contentWriter = contentWriter;
        this.clock = clock;
    }

    /** Bản sao (cùng tên, mô tả) đặt NGAY SAU section gốc, kèm task đang mở (cây con đang mở, label). */
    public SectionResponse duplicate(UUID userId, UUID projectId, UUID sectionId) {
        Project project = projectService.requireWritable(userId, projectId, ErrorMessage.SECTION_NOT_FOUND);
        Section source = requireActiveInProject(projectId, sectionId);
        usageLimits.lockProject(projectId);
        projectService.recheckAfterLock(project, false);
        sectionService.refreshWritable(source);

        List<NewTask> tasks = contentReader.readSection(projectId, sectionId);
        usageLimits.check(LimitKind.PROJECT_SECTIONS, sectionRepository.countByProjectIdAndArchivedAtIsNull(projectId), 1);
        usageLimits.check(LimitKind.PROJECT_SECTIONS_TOTAL, sectionRepository.countByProjectId(projectId), 1);
        usageLimits.check(LimitKind.PROJECT_ACTIVE_TASKS, taskRepository.countOpenInProject(projectId),
                tasks.stream().mapToInt(SectionTransferService::count).sum());

        List<Section> siblings = sectionService.container(projectId);
        SortOrderService.Placement placement = sortOrderService.placeAfter(items(siblings), sectionId);
        applyRenumber(siblings, placement);
        Section copy = sectionRepository.save(
                new Section(projectId, source.getName(), source.getDescription(), placement.sortOrder()));
        contentWriter.writeIntoNewSection(project, copy, tasks, userId);
        return toResponse(copy);
    }

    /**
     * Section + MỌI task của nó (đang mở và đã xong) sang dự án đích (cùng workspace). Task đi theo nhờ FK ghép ON
     * UPDATE CASCADE (V15); updated_at của task được ghi lại, version KHÔNG tăng (như relocate của task move, spec 5.6).
     */
    public SectionResponse move(UUID userId, UUID projectId, UUID sectionId, MoveSectionRequest request) {
        Project source = projectService.requireWritable(userId, projectId, ErrorMessage.SECTION_NOT_FOUND);
        Section section = requireActiveInProject(projectId, sectionId);
        Project target = projectService.requireTarget(userId, request.projectId()); // 404 / 422 target-archived
        if (!target.getWorkspace().getId().equals(source.getWorkspace().getId())) {
            throw ApiException.badRequest(ErrorMessage.CROSS_WORKSPACE_MOVE);
        }
        usageLimits.lockProjects(List.of(source.getId(), target.getId()));
        projectService.recheckAfterLock(source, target);
        sectionService.refreshWritable(section);
        if (!section.getProjectId().equals(projectId)) { // vừa bị chuyển đi bởi request khác
            throw ApiException.notFound(ErrorMessage.SECTION_NOT_FOUND);
        }
        boolean otherProject = !target.getId().equals(source.getId());
        if (otherProject) {
            usageLimits.check(LimitKind.PROJECT_SECTIONS,
                    sectionRepository.countByProjectIdAndArchivedAtIsNull(target.getId()), 1);
            usageLimits.check(LimitKind.PROJECT_SECTIONS_TOTAL, sectionRepository.countByProjectId(target.getId()), 1);
            usageLimits.check(LimitKind.PROJECT_ACTIVE_TASKS, taskRepository.countOpenInProject(target.getId()),
                    countOpenInSection(sectionId));
        }
        List<Section> siblings = sectionService.container(target.getId()).stream()
                .filter(s -> !s.getId().equals(sectionId)).toList();
        SortOrderService.Placement placement = sortOrderService.placeAt(items(siblings), request.afterId());
        applyRenumber(siblings, placement);
        section.moveTo(target.getId(), placement.sortOrder());
        sectionRepository.flush(); // DB đổi tasks.project_id theo (ON UPDATE CASCADE)
        if (otherProject) {
            entityManager.createNativeQuery("update tasks set updated_at = :now where section_id = :sectionId")
                    .setParameter("now", clock.instant())
                    .setParameter("sectionId", sectionId)
                    .executeUpdate();
            entityManager.clear(); // entity task đã nạp (nếu có) mang project_id cũ
            section = sectionService.requireInProject(target.getId(), sectionId);
        }
        return toResponse(section);
    }

    private Section requireActiveInProject(UUID projectId, UUID sectionId) {
        Section section = sectionService.requireInProject(projectId, sectionId);
        if (section.isArchived()) {
            throw SectionService.sectionArchived();
        }
        return section;
    }

    private long countOpenInSection(UUID sectionId) {
        return entityManager.createQuery("select count(t) from Task t where t.sectionId = :sectionId "
                        + "and t.completedAt is null", Long.class)
                .setParameter("sectionId", sectionId)
                .getSingleResult();
    }

    private static int count(NewTask task) {
        return 1 + task.children().stream().mapToInt(SectionTransferService::count).sum();
    }

    private static List<SortOrderService.Item> items(List<Section> sections) {
        return sections.stream().map(s -> new SortOrderService.Item(s.getId(), s.getSortOrder())).toList();
    }

    private static void applyRenumber(List<Section> siblings, SortOrderService.Placement placement) {
        if (placement.renumbers()) {
            siblings.forEach(s -> s.setSortOrder(placement.renumbered().get(s.getId())));
        }
    }

    private SectionResponse toResponse(Section section) {
        sectionRepository.flush();
        long open = sectionRepository.countOpenTasks(List.of(section.getId())).stream()
                .mapToLong(SectionRepository.SectionTaskCount::getTaskCount).sum();
        return SectionMapper.toResponse(section, open);
    }
}
