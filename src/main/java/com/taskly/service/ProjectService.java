package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.common.Created;
import com.taskly.dto.project.CreateProjectRequest;
import com.taskly.dto.project.MoveProjectRequest;
import com.taskly.dto.project.ProjectResponse;
import com.taskly.dto.project.UpdateProjectRequest;
import com.taskly.entity.FavoriteTargetType;
import com.taskly.entity.LimitKind;
import com.taskly.entity.Project;
import com.taskly.entity.ProjectColor;
import com.taskly.entity.ViewStyle;
import com.taskly.entity.Workspace;
import com.taskly.mapper.ProjectMapper;
import com.taskly.repository.ProjectRepository;
import com.taskly.security.AccessPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sở hữu bảng projects. Cung cấp cho service khác các phép tra cứu có kiểm quyền:
 * requireReadable (404), requireWritable (404, lưu trữ → 409 project-archived), requireTarget (đích tạo / chuyển:
 * 404, lưu trữ → 422 target-archived).
 */
@Service
@Transactional
public class ProjectService {

    public static final String INBOX_NAME = Project.INBOX_NAME;

    private final ProjectRepository projectRepository;
    private final WorkspaceService workspaceService;
    private final AccessPolicy accessPolicy;
    private final FavoriteService favoriteService;
    private final SortOrderService sortOrderService;
    private final ProjectTreeService projectTreeService;
    private final UsageLimits usageLimits;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ProjectService(ProjectRepository projectRepository, WorkspaceService workspaceService,
                          AccessPolicy accessPolicy, FavoriteService favoriteService,
                          SortOrderService sortOrderService, ProjectTreeService projectTreeService,
                          UsageLimits usageLimits, ApplicationEventPublisher events, Clock clock) {
        this.projectRepository = projectRepository;
        this.workspaceService = workspaceService;
        this.accessPolicy = accessPolicy;
        this.favoriteService = favoriteService;
        this.sortOrderService = sortOrderService;
        this.projectTreeService = projectTreeService;
        this.usageLimits = usageLimits;
        this.events = events;
        this.clock = clock;
    }

    /** Inbox của workspace cá nhân: is_inbox = true, không màu, không cha, layout LIST, đứng đầu (sort_order 0). */
    public Project createInbox(Workspace personalWorkspace) {
        return projectRepository.save(Project.inbox(personalWorkspace));
    }

    // ---- API ----

    /** Toàn bộ (không phân trang): Inbox trước rồi theo cây (cha trước con, anh em theo sort_order) — quyết định Q11. */
    @Transactional(readOnly = true)
    public List<ProjectResponse> list(UUID userId, boolean archived) {
        List<UUID> workspaceIds = accessPolicy.readableWorkspaceIds(userId);
        if (workspaceIds.isEmpty()) {
            return List.of();
        }
        List<Project> projects = archived
                ? projectRepository.findArchivedIn(workspaceIds)
                : projectRepository.findActiveIn(workspaceIds);
        return toResponses(userId, inTreeOrder(projects));
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(UUID userId, UUID projectId) {
        return toResponse(userId, requireReadable(userId, projectId));
    }

    /** ProjectResponse theo đúng thứ tự ids; id không thấy được bị bỏ qua (M2 search/objects dùng). */
    @Transactional(readOnly = true)
    public List<ProjectResponse> getResponses(UUID userId, List<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> readable = new HashSet<>(accessPolicy.readableWorkspaceIds(userId));
        Map<UUID, Project> byId = projectRepository.findAllById(projectIds).stream()
                .filter(p -> readable.contains(p.getWorkspace().getId()))
                .collect(Collectors.toMap(Project::getId, Function.identity()));
        return toResponses(userId, projectIds.stream().filter(byId::containsKey).map(byId::get).toList());
    }

    public Created<ProjectResponse> create(UUID userId, CreateProjectRequest request) {
        Workspace workspace = workspaceService.personalWorkspace(userId);
        if (request.id() != null) { // `id` client-sinh đã có → replay / 409 id-conflict (spec 12.11), TRƯỚC mọi kiểm khác
            Optional<Project> existing = projectRepository.findById(request.id());
            if (existing.isPresent()) {
                return replayOrConflict(userId, workspace, existing.get(), request);
            }
        }
        UUID parentId = request.parentId();
        if (parentId != null) {
            requireParent(userId, workspace.getId(), parentId);
            projectTreeService.checkCanAttach(tree(workspace.getId()), null, parentId);
        }
        usageLimits.lockWorkspace(workspace.getId()); // chống đua (spec 8.2 #9): khoá hàng cha TRƯỚC khi đếm
        usageLimits.check(LimitKind.ACTIVE_PROJECTS, projectRepository.countActive(workspace.getId()), 1);
        usageLimits.check(LimitKind.PROJECTS, projectRepository.countAll(workspace.getId()), 1);
        long sortOrder = placeForInsert(container(workspace.getId(), parentId), request.afterId());
        Project project = new Project(workspace, parentId, request.name(),
                request.description() == null ? "" : request.description(),
                request.color() == null ? ProjectColor.charcoal : request.color(),
                false, ViewStyle.LIST, sortOrder);
        project.assignId(request.id()); // null → id do server sinh
        projectRepository.save(project);
        return Created.fresh(toResponse(userId, project));
    }

    public ProjectResponse update(UUID userId, UUID projectId, UpdateProjectRequest request) {
        Project project = requireWritable(userId, projectId); // Inbox không bao giờ lưu trữ → rơi xuống 403 dưới
        rejectInbox(project);
        if (request.name() != null) {
            project.rename(request.name());
        }
        if (request.color() != null) {
            project.changeColor(request.color());
        }
        if (request.description() != null) {
            project.setDescription(request.description());
        }
        projectRepository.flush(); // để updatedAt trong response là giá trị đã ghi (không đổi nếu không có gì đổi)
        return toResponse(userId, project);
    }

    /** Đặt / bỏ yêu thích — được cả với dự án lưu trữ (spec 5.7); Inbox → 403. */
    public ProjectResponse setFavorite(UUID userId, UUID projectId, boolean favorite) {
        Project project = requireReadable(userId, projectId);
        rejectInbox(project);
        if (favorite) {
            favoriteService.add(userId, FavoriteTargetType.PROJECT, projectId);
        } else {
            favoriteService.remove(userId, FavoriteTargetType.PROJECT, projectId);
        }
        return toResponse(userId, project);
    }

    /**
     * Đổi cha (null = gốc) + vị trí (spec 10.1, 12.2): cả cây con đi theo; độ sâu sau khi chuyển (tính cả cây con)
     * ≤ 3; vòng lặp / Inbox làm cha → 400; Inbox bị chuyển → 403; cha lưu trữ → 422; dự án lưu trữ → 409.
     */
    public ProjectResponse move(UUID userId, UUID projectId, MoveProjectRequest request) {
        Project project = requireReadable(userId, projectId);
        rejectInbox(project);
        accessPolicy.checkWrite(userId, project.getWorkspace().getId(), ErrorMessage.PROJECT_NOT_FOUND);
        requireNotArchived(project);
        UUID workspaceId = project.getWorkspace().getId();
        UUID parentId = request.parentId().orElse(null); // parentId bắt buộc có khoá; null = gốc
        if (parentId != null) {
            requireParent(userId, workspaceId, parentId);
        }
        projectTreeService.checkCanAttach(tree(workspaceId), projectId, parentId);
        List<Project> siblings = container(workspaceId, parentId).stream()
                .filter(p -> !p.getId().equals(projectId))
                .toList();
        project.moveUnder(parentId, placeForInsert(siblings, request.afterId()));
        projectRepository.flush();
        return toResponse(userId, project);
    }

    /**
     * Lưu trữ dự án + mọi dự án con chưa lưu trữ, cùng một thời điểm (spec 5.7). Lặp lại → 200 không ghi đè.
     * Task / section không đổi; dự án lưu trữ thành chỉ đọc.
     */
    public ProjectResponse archive(UUID userId, UUID projectId) {
        Project project = requireReadable(userId, projectId);
        rejectInbox(project);
        accessPolicy.checkWrite(userId, project.getWorkspace().getId(), ErrorMessage.PROJECT_NOT_FOUND);
        if (!project.isArchived()) {
            Instant now = clock.instant();
            projectRepository.findAllById(withDescendants(project)).forEach(p -> p.archive(now));
            projectRepository.flush();
        }
        return toResponse(userId, project);
    }

    /**
     * Khôi phục dự án + các dự án con có CÙNG archived_at (spec 5.7, 10.2); cha còn lưu trữ → thành dự án gốc, đặt
     * cuối vùng chứa gốc. Vượt 300 dự án đang hoạt động → 422. Dự án đang hoạt động → 200 không đổi.
     */
    public ProjectResponse unarchive(UUID userId, UUID projectId) {
        Project project = requireReadable(userId, projectId);
        rejectInbox(project);
        accessPolicy.checkWrite(userId, project.getWorkspace().getId(), ErrorMessage.PROJECT_NOT_FOUND);
        if (project.isArchived()) {
            UUID workspaceId = project.getWorkspace().getId();
            Instant archivedAt = project.getArchivedAt();
            List<Project> restored = projectRepository.findAllById(withDescendants(project)).stream()
                    .filter(p -> archivedAt.equals(p.getArchivedAt()))
                    .toList();
            usageLimits.lockWorkspace(workspaceId); // chống đua (spec 8.2 #9): khoá TRƯỚC khi đếm
            usageLimits.check(LimitKind.ACTIVE_PROJECTS, projectRepository.countActive(workspaceId), restored.size());
            if (project.getParentId() != null && projectRepository.findById(project.getParentId())
                    .map(Project::isArchived).orElse(false)) {
                project.moveUnder(null, placeForInsert(container(workspaceId, null), null));
            }
            restored.forEach(Project::unarchive);
            projectRepository.flush();
        }
        return toResponse(userId, project);
    }

    /**
     * Xoá VĨNH VIỄN (spec 5.7, 10.2): dự án con, section, task bị DB xoá theo (ON DELETE CASCADE); yêu thích của mọi
     * dự án trong cây xoá tay; phát ProjectsDeletedEvent cho M2. Dự án lưu trữ vẫn xoá được; Inbox → 403.
     */
    public void delete(UUID userId, UUID projectId) {
        Project project = requireReadable(userId, projectId);
        rejectInbox(project);
        accessPolicy.checkWrite(userId, project.getWorkspace().getId(), ErrorMessage.PROJECT_NOT_FOUND);
        List<UUID> ids = withDescendants(project);
        favoriteService.removeAllFor(FavoriteTargetType.PROJECT, ids);
        events.publishEvent(new ProjectsDeletedEvent(ids));
        projectRepository.delete(project);
        projectRepository.flush();
    }

    // ---- Tra cứu cho service khác ----

    public Project requireReadable(UUID userId, UUID projectId) {
        return requireReadable(userId, projectId, ErrorMessage.PROJECT_NOT_FOUND);
    }

    /** notFoundDetail: câu 404 theo ngữ cảnh (vd tra dự án của một task → "Không tìm thấy công việc"). */
    public Project requireReadable(UUID userId, UUID projectId, ErrorMessage notFoundDetail) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> ApiException.notFound(notFoundDetail));
        accessPolicy.checkRead(userId, project.getWorkspace().getId(), notFoundDetail);
        return project;
    }

    public Project requireWritable(UUID userId, UUID projectId) {
        return requireWritable(userId, projectId, ErrorMessage.PROJECT_NOT_FOUND);
    }

    /** Như requireReadable + dự án lưu trữ là chỉ đọc → 409 project-archived (spec 5.7). */
    public Project requireWritable(UUID userId, UUID projectId, ErrorMessage notFoundDetail) {
        Project project = requireReadable(userId, projectId, notFoundDetail);
        accessPolicy.checkWrite(userId, project.getWorkspace().getId(), notFoundDetail);
        requireNotArchived(project);
        return project;
    }

    /** Dự án đích khi tạo / chuyển task (BR-08): không thấy → 404; của mình nhưng đang lưu trữ → 422 target-archived. */
    public Project requireTarget(UUID userId, UUID projectId) {
        Project project = requireReadable(userId, projectId);
        accessPolicy.checkWrite(userId, project.getWorkspace().getId(), ErrorMessage.PROJECT_NOT_FOUND);
        if (project.isArchived()) {
            throw targetArchived();
        }
        return project;
    }

    public Project inboxOf(UUID userId) {
        return projectRepository.findInboxOfUser(userId)
                .orElseThrow(() -> ApiException.unauthorized(ErrorMessage.SESSION_INVALID));
    }

    /** Vùng chứa sắp xếp: dự án thường cùng (workspace, parentId) theo (sortOrder, id), gồm cả lưu trữ. */
    public List<Project> container(UUID workspaceId, UUID parentId) {
        return projectRepository.findContainer(workspaceId, parentId);
    }

    // ---- nội bộ ----

    /** Cha hợp lệ của dự án: thấy được (404), không phải Inbox (400), cùng workspace (400), chưa lưu trữ (422). */
    private Project requireParent(UUID userId, UUID workspaceId, UUID parentId) {
        Project parent = requireReadable(userId, parentId);
        if (parent.isInbox()) {
            throw ApiException.badRequest(ErrorMessage.INBOX_NOT_NESTABLE);
        }
        if (!parent.getWorkspace().getId().equals(workspaceId)) {
            throw ApiException.badRequest(ErrorMessage.CROSS_WORKSPACE_MOVE);
        }
        if (parent.isArchived()) {
            throw targetArchived();
        }
        return parent;
    }

    private ProjectTreeService.Tree tree(UUID workspaceId) {
        return projectTreeService.tree(projectRepository.findTreeRows(workspaceId).stream()
                .map(row -> new ProjectTreeService.Node((UUID) row[0], (UUID) row[1], (Long) row[2]))
                .toList());
    }

    /** Id của dự án + mọi dự án con cháu (theo cây của workspace, gồm cả dự án lưu trữ). */
    private List<UUID> withDescendants(Project project) {
        List<UUID> ids = new ArrayList<>();
        ids.add(project.getId());
        ids.addAll(projectTreeService.descendants(tree(project.getWorkspace().getId()), project.getId()));
        return ids;
    }

    /** afterId null → CUỐI vùng chứa. siblings không gồm dự án đang đặt. Tự ghi đánh số lại. */
    private long placeForInsert(List<Project> siblings, Optional<UUID> afterId) { // null = vắng = cuối; empty = đầu
        SortOrderService.Placement placement = sortOrderService.placeAt(items(siblings), afterId);
        if (placement.renumbers()) {
            siblings.forEach(p -> p.setSortOrder(placement.renumbered().get(p.getId())));
        }
        return placement.sortOrder();
    }

    /** Inbox trước, rồi duyệt cây trong chính tập này (dự án có cha ngoài tập coi là gốc). */
    private List<Project> inTreeOrder(List<Project> projects) {
        Map<UUID, Project> byId = projects.stream().collect(Collectors.toMap(Project::getId, Function.identity()));
        List<Project> ordered = new ArrayList<>(projects.stream().filter(Project::isInbox).toList());
        ProjectTreeService.Tree tree = projectTreeService.tree(projects.stream().filter(p -> !p.isInbox())
                .map(p -> new ProjectTreeService.Node(p.getId(), p.getParentId(), p.getSortOrder())).toList());
        projectTreeService.displayOrder(tree).forEach(id -> ordered.add(byId.get(id)));
        return ordered;
    }

    private static void rejectInbox(Project project) {
        if (project.isInbox()) {
            throw ApiException.forbidden(ErrorMessage.INBOX_PROTECTED);
        }
    }

    private static void requireNotArchived(Project project) {
        if (project.isArchived()) {
            throw ApiException.conflict(ErrorMessage.PROJECT_ARCHIVED).withCode(ProblemCodes.PROJECT_ARCHIVED);
        }
    }

    private static ApiException targetArchived() {
        return ApiException.unprocessable(ErrorMessage.TARGET_ARCHIVED).withCode(ProblemCodes.TARGET_ARCHIVED);
    }

    private static List<SortOrderService.Item> items(List<Project> projects) {
        return projects.stream().map(p -> new SortOrderService.Item(p.getId(), p.getSortOrder())).toList();
    }

    /**
     * `id` client-sinh đã tồn tại (spec 12.11): của CÙNG user (cùng workspace) và các field nội dung của yêu cầu tạo
     * (name, description, color, parentId — bỏ qua afterId) khớp → 200 + Idempotent-Replay, KHÔNG ghi, KHÔNG đếm lại giới
     * hạn; khác nội dung hoặc của người khác → 409 id-conflict (cùng một câu trả lời, không lộ gì thêm).
     */
    private Created<ProjectResponse> replayOrConflict(UUID userId, Workspace workspace, Project existing,
                                                      CreateProjectRequest request) {
        boolean mine = existing.getWorkspace().getId().equals(workspace.getId());
        if (!mine || !sameContent(existing, request)) {
            throw ApiException.conflict(ErrorMessage.ID_CONFLICT).withCode(ProblemCodes.ID_CONFLICT);
        }
        return Created.replay(toResponse(userId, existing));
    }

    private static boolean sameContent(Project project, CreateProjectRequest request) {
        return project.getName().equals(request.name())
                && (request.description() == null ? "" : request.description()).equals(project.getDescription())
                && (request.color() == null ? ProjectColor.charcoal : request.color()) == project.getColor()
                && Objects.equals(request.parentId(), project.getParentId());
    }

    /** Số dự án con cháu mọi cấp (kể cả lưu trữ) + tổng task đang mở của chúng — cho hộp xác nhận xoá (spec 10.1, I11). */
    private record DescendantStats(long projects, long activeTasks) {
        static final DescendantStats NONE = new DescendantStats(0, 0);
    }

    private Map<UUID, Long> countOpen(Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return projectRepository.countOpenTasks(projectIds).stream()
                .collect(Collectors.toMap(ProjectRepository.ProjectTaskCount::getProjectId,
                        ProjectRepository.ProjectTaskCount::getTaskCount));
    }

    private ProjectResponse toResponse(UUID userId, Project project) {
        return toResponses(userId, List.of(project)).get(0);
    }

    private List<ProjectResponse> toResponses(UUID userId, List<Project> projects) {
        if (projects.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = projects.stream().map(Project::getId).toList();
        Set<UUID> favorites = favoriteService.favoriteIds(userId, FavoriteTargetType.PROJECT, ids);
        // Cây dự án của từng workspace (kể cả lưu trữ) → descendantProjectCount / descendantActiveTaskCount
        Map<UUID, Long> counts = new HashMap<>();
        Map<UUID, DescendantStats> stats = new HashMap<>();
        for (UUID workspaceId : projects.stream().map(p -> p.getWorkspace().getId()).collect(Collectors.toSet())) {
            ProjectTreeService.Tree tree = tree(workspaceId);
            Map<UUID, Long> workspaceCounts = countOpen(tree.byId().keySet());
            counts.putAll(workspaceCounts);
            tree.byId().keySet().forEach(id -> {
                List<UUID> descendants = projectTreeService.descendants(tree, id);
                stats.put(id, new DescendantStats(descendants.size(),
                        descendants.stream().mapToLong(d -> workspaceCounts.getOrDefault(d, 0L)).sum()));
            });
        }
        counts.putAll(countOpen(ids.stream().filter(id -> !counts.containsKey(id)).toList())); // Inbox không nằm trong cây
        return projects.stream()
                .map(p -> {
                    DescendantStats descendants = stats.getOrDefault(p.getId(), DescendantStats.NONE);
                    return ProjectMapper.toResponse(p, favorites.contains(p.getId()), counts.getOrDefault(p.getId(), 0L),
                            descendants.projects(), descendants.activeTasks());
                })
                .toList();
    }
}
