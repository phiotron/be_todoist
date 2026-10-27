package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;
import com.taskly.common.error.ProblemTexts;
import com.taskly.dto.common.Created;
import com.taskly.dto.section.CreateSectionRequest;
import com.taskly.dto.section.SectionResponse;
import com.taskly.dto.section.UpdateSectionRequest;
import com.taskly.entity.LimitKind;
import com.taskly.entity.Project;
import com.taskly.entity.Section;
import com.taskly.mapper.SectionMapper;
import com.taskly.repository.SectionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sở hữu bảng sections. Quyền đi theo dự án chứa section (ProjectService → AccessPolicy).
 * Section lưu trữ (spec 5.7): task của nó chỉ đọc (409 section-archived); bản thân section vẫn đổi tên / sửa mô tả /
 * xoá / khôi phục được; không sắp xếp được. Xoá section → DB xoá mọi task của nó (ON DELETE CASCADE).
 */
@Service
@Transactional
public class SectionService {

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private final SectionRepository sectionRepository;
    private final ProjectService projectService;
    private final SortOrderService sortOrderService;
    private final UsageLimits usageLimits;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public SectionService(SectionRepository sectionRepository, ProjectService projectService,
                          SortOrderService sortOrderService, UsageLimits usageLimits,
                          ApplicationEventPublisher events, Clock clock) {
        this.sectionRepository = sectionRepository;
        this.projectService = projectService;
        this.sortOrderService = sortOrderService;
        this.usageLimits = usageLimits;
        this.events = events;
        this.clock = clock;
    }

    // ---- API ----

    /** Section chưa lưu trữ theo sort_order, rồi section lưu trữ (lưu trữ mới nhất trước). */
    @Transactional(readOnly = true)
    public List<SectionResponse> list(UUID userId, UUID projectId) {
        Project project = projectService.requireReadable(userId, projectId);
        List<Section> sections = new ArrayList<>(container(project.getId()));
        sections.addAll(sectionRepository.findByProjectIdAndArchivedAtIsNotNullOrderByArchivedAtDescIdAsc(project.getId()));
        return toResponses(sections);
    }

    public Created<SectionResponse> create(UUID userId, UUID projectId, CreateSectionRequest request) {
        projectService.requireReadable(userId, projectId, ProblemTexts.PROJECT_NOT_FOUND); // 404 trước (dự án người khác)
        if (request.id() != null) { // `id` client-sinh đã có → replay / 409 id-conflict (spec 12.11)
            Optional<Section> existing = sectionRepository.findById(request.id());
            if (existing.isPresent()) {
                return replayOrConflict(projectId, existing.get(), request);
            }
        }
        Project project = projectService.requireWritable(userId, projectId); // 409 project-archived
        usageLimits.lockProject(project.getId()); // chống đua (spec 8.2 #9): khoá hàng cha TRƯỚC khi đếm
        projectService.recheckAfterLock(project, false); // đã chờ khoá: dự án có thể vừa được lưu trữ → 409
        usageLimits.check(LimitKind.PROJECT_SECTIONS,
                sectionRepository.countByProjectIdAndArchivedAtIsNull(project.getId()), 1);
        usageLimits.check(LimitKind.PROJECT_SECTIONS_TOTAL, sectionRepository.countByProjectId(project.getId()), 1);
        List<Section> siblings = container(project.getId());
        SortOrderService.Placement placement = sortOrderService.placeAt(items(siblings), request.afterId());
        applyRenumber(siblings, placement);
        Section section = new Section(project.getId(), request.name(),
                request.description() == null ? "" : request.description(), placement.sortOrder());
        section.assignId(request.id()); // null → id do server sinh
        sectionRepository.save(section);
        return Created.fresh(toResponse(section));
    }

    /**
     * `id` client-sinh đã tồn tại (spec 12.11): cùng dự án trong path + cùng name / description (bỏ qua afterId) → 200 +
     * Idempotent-Replay, KHÔNG ghi, KHÔNG đếm lại giới hạn; khác nội dung / thuộc dự án khác (của người khác) → 409
     * id-conflict (không lộ gì thêm).
     */
    private Created<SectionResponse> replayOrConflict(UUID projectId, Section existing, CreateSectionRequest request) {
        boolean same = existing.getProjectId().equals(projectId)
                && existing.getName().equals(request.name())
                && (request.description() == null ? "" : request.description()).equals(existing.getDescription());
        if (!same) {
            throw ApiException.conflict(ProblemTexts.ID_CONFLICT).withCode(ProblemCodes.ID_CONFLICT);
        }
        return Created.replay(toResponse(existing));
    }

    /** Đổi tên / mô tả — được cả với section lưu trữ; dự án lưu trữ → 409. */
    public SectionResponse update(UUID userId, UUID projectId, UUID sectionId, UpdateSectionRequest request) {
        Project project = projectService.requireWritable(userId, projectId, ProblemTexts.SECTION_NOT_FOUND);
        // cùng quy tắc mọi ghi dưới dự án: khoá P rồi đọc lại, TRƯỚC khi nạp section (xoá / lưu trữ song song)
        usageLimits.lockProject(projectId);
        projectService.recheckAfterLock(project, false);
        Section section = requireInProject(projectId, sectionId);
        if (request.name() != null) {
            section.rename(request.name());
        }
        if (request.description() != null) {
            section.setDescription(request.description());
        }
        sectionRepository.flush();
        return toResponse(section);
    }

    /** Xoá VĨNH VIỄN section + mọi task của nó (đang mở + đã xong, kèm cây con) — spec 5.7. Dự án lưu trữ → 409. */
    public void delete(UUID userId, UUID projectId, UUID sectionId) {
        Project project = projectService.requireWritable(userId, projectId, ProblemTexts.SECTION_NOT_FOUND);
        // khoá P TRƯỚC khi xoá: task create vừa kiểm section xong (đang chờ P) sẽ chèn xong rồi mới tới lượt xoá (cascade),
        // thay vì chèn vào section đã biến mất (lỗi toàn vẹn chung)
        usageLimits.lockProject(projectId);
        projectService.recheckAfterLock(project, false);
        Section section = requireInProject(projectId, sectionId);
        sectionRepository.delete(section);
        sectionRepository.flush();
    }

    /**
     * Lưu trữ section (spec 5.7, 10.2): archived_at = now; mọi task đang mở trong section (kèm cây con) thành đã xong
     * cùng thời điểm (qua SectionArchivedEvent → TaskService). Lặp lại → 200 không đổi. Dự án lưu trữ → 409.
     */
    public SectionResponse archive(UUID userId, UUID projectId, UUID sectionId) {
        projectService.requireWritable(userId, projectId, ProblemTexts.SECTION_NOT_FOUND);
        // chống đua (spec 8.2 #9): tuần tự hoá với tạo / chuyển task cùng dự án (chúng khoá dự án rồi đọc lại cờ
        // lưu trữ) → không còn task mở trong section đã lưu trữ. Khoá TRƯỚC khi nạp section.
        usageLimits.lockProject(projectId);
        Section section = requireInProject(projectId, sectionId);
        if (!section.isArchived()) {
            Instant now = clock.instant();
            section.archive(now);
            events.publishEvent(new SectionArchivedEvent(section.getId(), now));
            sectionRepository.flush();
        }
        return toResponse(section);
    }

    /**
     * Khôi phục section: task GIỮ trạng thái đã xong; giữ sort_order cũ (Q34). Khoá dự án rồi mới đếm (spec 8.2 #9);
     * vượt 20 section đang hoạt động (PROJECT_SECTIONS) → 422. PROJECT_SECTIONS_TOTAL (tổng gồm cả section lưu trữ,
     * <= 100) KHÔNG đổi khi khôi phục nên không kiểm lại.
     */
    public SectionResponse unarchive(UUID userId, UUID projectId, UUID sectionId) {
        Project project = projectService.requireWritable(userId, projectId, ProblemTexts.SECTION_NOT_FOUND);
        // cùng quy tắc mọi ghi dưới dự án: khoá P → đọc lại dự án → MỚI nạp section (xoá / lưu trữ / khôi phục song song)
        usageLimits.lockProject(projectId);
        projectService.recheckAfterLock(project, false);
        Section section = requireInProject(projectId, sectionId);
        if (section.isArchived()) {
            usageLimits.check(LimitKind.PROJECT_SECTIONS, sectionRepository.countByProjectIdAndArchivedAtIsNull(projectId), 1);
            section.unarchive();
            sectionRepository.flush();
        }
        return toResponse(section);
    }

    // ---- Tra cứu cho service khác ----

    /** Section của đúng dự án trong path, không thì 404. Quyền với dự án do caller kiểm trước. */
    public Section requireInProject(UUID projectId, UUID sectionId) {
        return sectionRepository.findByIdAndProjectId(sectionId, projectId)
                .orElseThrow(() -> ApiException.notFound(ProblemTexts.SECTION_NOT_FOUND));
    }

    /** Section theo id (reorder): 404; dự án lưu trữ → 409 project-archived; section lưu trữ → 409 section-archived. */
    public Section requireWritable(UUID userId, UUID sectionId) {
        Section section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> ApiException.notFound(ProblemTexts.SECTION_NOT_FOUND));
        projectService.requireWritable(userId, section.getProjectId(), ProblemTexts.SECTION_NOT_FOUND);
        if (section.isArchived()) {
            throw sectionArchived();
        }
        return section;
    }

    /**
     * Section đích khi tạo / chuyển task vào projectId (quyết định Q20): không tồn tại hoặc của người khác → 404;
     * thuộc dự án khác → 400; đang lưu trữ → 422 target-archived.
     */
    public Section requireTargetSection(UUID userId, UUID projectId, UUID sectionId) {
        Section section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> ApiException.notFound(ProblemTexts.SECTION_NOT_FOUND));
        projectService.requireReadable(userId, section.getProjectId(), ProblemTexts.SECTION_NOT_FOUND);
        if (!section.getProjectId().equals(projectId)) {
            throw ApiException.badRequest(ProblemTexts.SECTION_NOT_IN_PROJECT);
        }
        if (section.isArchived()) {
            throw ApiException.unprocessable(ProblemTexts.TARGET_ARCHIVED).withCode(ProblemCodes.TARGET_ARCHIVED);
        }
        return section;
    }

    /**
     * Gọi SAU khi đã khoá dự án chứa section: đọc thẳng DB (bỏ qua entity đã nạp trước khoá). Section đã bị xoá → 404;
     * đang lưu trữ → parentContext (đích là cha của task tạo) 409 section-archived, ngược lại 422 target-archived.
     */
    public void recheckAfterLock(UUID sectionId, boolean parentContext) {
        if (!sectionRepository.existsById(sectionId)) {
            throw ApiException.notFound(ProblemTexts.SECTION_NOT_FOUND);
        }
        if (sectionRepository.existsByIdAndArchivedAtIsNotNull(sectionId)) {
            throw parentContext ? sectionArchived()
                    : ApiException.unprocessable(ProblemTexts.TARGET_ARCHIVED).withCode(ProblemCodes.TARGET_ARCHIVED);
        }
    }

    /** Đọc lại section đã nạp trước khoá (reorder, sau khi khoá P): đã xoá → 404; đã lưu trữ → 409 section-archived. */
    public Section refreshWritable(Section section) {
        try {
            entityManager.refresh(section);
        } catch (jakarta.persistence.EntityNotFoundException e) {
            throw ApiException.notFound(ProblemTexts.SECTION_NOT_FOUND);
        }
        if (section.isArchived()) {
            throw sectionArchived();
        }
        return section;
    }

    /** true nếu section tồn tại và đang lưu trữ (TaskService dùng để chặn ghi lên task của section lưu trữ). */
    public boolean isArchived(UUID sectionId) {
        return sectionRepository.findById(sectionId).map(Section::isArchived).orElse(false);
    }

    /** Như isArchived nhưng đọc thẳng DB (bỏ qua entity đã nạp) — dùng SAU khi khoá dự án. */
    public boolean isArchivedNow(UUID sectionId) {
        return sectionRepository.existsByIdAndArchivedAtIsNotNull(sectionId);
    }

    /** Vùng chứa sắp xếp: section CHƯA lưu trữ của dự án theo (sortOrder, id). */
    public List<Section> container(UUID projectId) {
        return sectionRepository.findByProjectIdAndArchivedAtIsNullOrderBySortOrderAscIdAsc(projectId);
    }

    public static ApiException sectionArchived() {
        return ApiException.conflict(ProblemTexts.SECTION_ARCHIVED).withCode(ProblemCodes.SECTION_ARCHIVED);
    }

    // ---- nội bộ ----

    private static List<SortOrderService.Item> items(List<Section> sections) {
        return sections.stream().map(s -> new SortOrderService.Item(s.getId(), s.getSortOrder())).toList();
    }

    private static void applyRenumber(List<Section> siblings, SortOrderService.Placement placement) {
        if (placement.renumbers()) {
            siblings.forEach(s -> s.setSortOrder(placement.renumbered().get(s.getId())));
        }
    }

    private SectionResponse toResponse(Section section) {
        return toResponses(List.of(section)).get(0);
    }

    private List<SectionResponse> toResponses(List<Section> sections) {
        if (sections.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> counts = sectionRepository.countOpenTasks(sections.stream().map(Section::getId).toList())
                .stream().collect(Collectors.toMap(SectionRepository.SectionTaskCount::getSectionId,
                        SectionRepository.SectionTaskCount::getTaskCount));
        return sections.stream().map(s -> SectionMapper.toResponse(s, counts.getOrDefault(s.getId(), 0L))).toList();
    }
}
