package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.common.Created;
import com.taskly.dto.project.CreateProjectRequest;
import com.taskly.dto.project.MoveProjectRequest;
import com.taskly.dto.project.ProjectCapabilities;
import com.taskly.dto.project.ProjectResponse;
import com.taskly.dto.project.UpdateProjectRequest;
import com.taskly.entity.ActivityEventType;
import com.taskly.dto.sharing.MoveToWorkspaceRequest;
import com.taskly.entity.FavoriteTargetType;
import com.taskly.entity.LimitKind;
import com.taskly.entity.Project;
import com.taskly.entity.ProjectAccess;
import com.taskly.entity.ProjectColor;
import com.taskly.entity.ProjectMember;
import com.taskly.repository.FolderRepository;
import com.taskly.repository.ProjectMemberRepository;
import com.taskly.security.ReadScope;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

    @PersistenceContext
    private EntityManager entityManager;

    private final ProjectRepository projectRepository;
    private final WorkspaceService workspaceService;
    private final AccessPolicy accessPolicy;
    private final FavoriteService favoriteService;
    private final SortOrderService sortOrderService;
    private final ProjectTreeService projectTreeService;
    private final UsageLimits usageLimits;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ActivityService activityService; // M10
    private final ProjectMemberRepository projectMemberRepository;
    private final FolderRepository folderRepository;
    private final MembershipService membership;
    private final UserService userService;

    public ProjectService(ProjectRepository projectRepository, WorkspaceService workspaceService,
                          AccessPolicy accessPolicy, FavoriteService favoriteService,
                          SortOrderService sortOrderService, ProjectTreeService projectTreeService,
                          UsageLimits usageLimits, ApplicationEventPublisher events, Clock clock,
                          ActivityService activityService,
                          ProjectMemberRepository projectMemberRepository, FolderRepository folderRepository,
                          MembershipService membership, UserService userService) {
        this.activityService = activityService;
        this.projectMemberRepository = projectMemberRepository;
        this.folderRepository = folderRepository;
        this.membership = membership;
        this.userService = userService;
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
        ReadScope scope = accessPolicy.listScope(userId); // M8a: dự án ĐÃ THAM GIA (gồm dự án được chia sẻ)
        if (scope.isEmpty()) {
            return List.of();
        }
        List<Project> projects = archived
                ? projectRepository.findArchivedAmong(scope.projectIds())
                : projectRepository.findActiveAmong(scope.projectIds());
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
        Set<UUID> readable = new HashSet<>(accessPolicy.listScope(userId).projectIds());
        Map<UUID, Project> byId = projectRepository.findAllById(projectIds).stream()
                .filter(p -> readable.contains(p.getId()))
                .collect(Collectors.toMap(Project::getId, Function.identity()));
        return toResponses(userId, projectIds.stream().filter(byId::containsKey).map(byId::get).toList());
    }

    public Created<ProjectResponse> create(UUID userId, CreateProjectRequest request) {
        Workspace workspace = targetWorkspace(userId, request); // M8a: workspace nhóm khi có workspaceId
        if (request.id() != null) { // `id` client-sinh đã có → replay / 409 id-conflict (spec 12.11), TRƯỚC mọi kiểm khác
            Optional<Project> existing = projectRepository.findById(request.id());
            if (existing.isPresent()) {
                return replayOrConflict(userId, workspace, existing.get(), request);
            }
        }
        UUID parentId = request.parentId();
        // Quy tắc khoá thao tác đổi CẤU TRÚC: workspace (W) TRƯỚC, rồi đọc/kiểm cha (đọc sau khoá = bản mới nhất), rồi mới
        // khoá hàng dự án (id tăng dần, chỉ khi đánh số lại anh em).
        usageLimits.lockWorkspace(workspace.getId()); // W: mutex CẤU TRÚC dự án (cũng là hàng cha của phép đếm) — TRƯỚC khi đếm
        if (parentId != null) {
            requireParent(userId, workspace.getId(), parentId);
            projectTreeService.checkCanAttach(tree(workspace.getId()), null, parentId);
        }
        usageLimits.check(LimitKind.ACTIVE_PROJECTS, projectRepository.countActive(workspace.getId()), 1);
        usageLimits.check(LimitKind.PROJECTS, projectRepository.countAll(workspace.getId()), 1);
        long sortOrder = placeForInsert(List.of(), container(workspace.getId(), parentId), request.afterId());
        Project project = new Project(workspace, parentId, request.name(),
                request.description() == null ? "" : request.description(),
                request.color() == null ? ProjectColor.charcoal : request.color(),
                false, ViewStyle.LIST, sortOrder);
        project.assignId(request.id()); // null → id do server sinh
        project.setCreatedBy(userId);
        if (workspace.isTeam()) { // M8a: dự án nhóm có access + folder; người tạo tự là thành viên dự án
            project.setAccess(request.access() == null ? ProjectAccess.TEAM : request.access());
            project.setFolderId(request.folderId());
        }
        projectRepository.save(project);
        if (workspace.isTeam()) {
            projectRepository.flush(); // hàng project phải có trước khi chèn project_members (FK)
            projectMemberRepository.save(new ProjectMember(project.getId(), userId));
        }
        activityService.project(userId, project, ActivityEventType.ADDED); // M10
        return Created.fresh(toResponse(userId, project));
    }

    /**
     * M8a: workspace đích của POST projects. Vắng / workspace cá nhân của mình → cá nhân (access, folderId phải vắng →
     * 400). Workspace nhóm: người ngoài 404, Guest 403; không có dự án con (parentId → 400); folder phải thuộc team (400).
     */
    private Workspace targetWorkspace(UUID userId, CreateProjectRequest request) {
        Workspace personal = workspaceService.personalWorkspace(userId);
        if (request.workspaceId() == null || request.workspaceId().equals(personal.getId())) {
            if (request.access() != null || request.folderId() != null) {
                throw ApiException.badRequest(ErrorMessage.TEAM_ONLY_SETTING);
            }
            return personal;
        }
        accessPolicy.requireTeamEditor(userId, request.workspaceId()); // PERM: chỉ Admin tạo dự án nhóm
        Workspace team = workspaceService.requireWorkspace(request.workspaceId());
        if (!team.isTeam()) {
            throw ApiException.notFound(ErrorMessage.WORKSPACE_NOT_FOUND);
        }
        if (request.parentId() != null) {
            throw ApiException.badRequest(ErrorMessage.TEAM_NO_SUBPROJECTS);
        }
        if (request.folderId() != null) {
            requireFolderIn(team.getId(), request.folderId());
        }
        return team;
    }

    private void requireFolderIn(UUID workspaceId, UUID folderId) {
        boolean valid = folderRepository.findById(folderId)
                .map(folder -> folder.getWorkspaceId().equals(workspaceId))
                .orElse(false);
        if (!valid) {
            throw ApiException.badRequest(ErrorMessage.FOLDER_INVALID);
        }
    }

    public ProjectResponse update(UUID userId, UUID projectId, UpdateProjectRequest request) {
        Project project = requireWritable(userId, projectId); // Inbox không bao giờ lưu trữ → rơi xuống 403 dưới
        rejectInbox(project);
        List<Object> tracked = ActivityService.trackedFields(project); // M10
        if (request.name() != null) {
            project.rename(request.name());
        }
        if (request.color() != null) {
            project.changeColor(request.color());
        }
        if (request.description() != null) {
            project.setDescription(request.description());
        }
        if (request.folderId() != null || request.access() != null) { // M8a: chỉ dự án nhóm + người quản lý
            if (!project.getWorkspace().isTeam()) {
                throw ApiException.badRequest(ErrorMessage.TEAM_ONLY_SETTING);
            }
            accessPolicy.checkProjectManage(userId, project);
            if (request.folderId() != null) {
                UUID folderId = request.folderId().orElse(null);
                if (folderId != null) {
                    requireFolderIn(project.getWorkspace().getId(), folderId);
                }
                project.setFolderId(folderId);
            }
            if (request.access() != null) {
                project.setAccess(request.access());
            }
        }
        if (!tracked.equals(ActivityService.trackedFields(project))) {
            activityService.project(userId, project, ActivityEventType.UPDATED); // M10
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
        accessPolicy.checkProjectManage(userId, project); // M8a: chỉ người quản lý (chủ / Admin / người tạo)
        requireNotArchived(project);
        UUID workspaceId = project.getWorkspace().getId();
        usageLimits.lockWorkspace(workspaceId); // chống đua (spec 8.2 #9): hai move ngược chiều không tạo vòng lặp
        refresh(project); // đã chờ khoá: archive(cha) có thể vừa commit và lưu trữ cả dự án này → đọc lại, kiểm lại cờ
        requireNotArchived(project);
        UUID parentId = request.parentId().orElse(null); // parentId bắt buộc có khoá; null = gốc
        if (parentId != null && project.getWorkspace().isTeam()) {
            throw ApiException.badRequest(ErrorMessage.TEAM_NO_SUBPROJECTS); // M8a: team không có dự án con
        }
        if (parentId != null) {
            requireParent(userId, workspaceId, parentId);
        }
        projectTreeService.checkCanAttach(tree(workspaceId), projectId, parentId);
        List<Project> siblings = container(workspaceId, parentId).stream()
                .filter(p -> !p.getId().equals(projectId))
                .toList();
        // dự án đang chuyển luôn bị khoá; anh em chỉ khi phải đánh số lại — id TĂNG DẦN, cùng thứ tự với task move
        project.moveUnder(parentId, placeForInsert(List.of(project), siblings, request.afterId()));
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
        accessPolicy.checkProjectManage(userId, project); // M8a: chỉ người quản lý (chủ / Admin / người tạo)
        usageLimits.lockWorkspace(project.getWorkspace().getId()); // W trước: tuần tự hoá với create / move / unarchive
        refresh(project); // bản nạp trước khoá có thể cũ: archive khác vừa commit → không ghi đè archived_at
        if (!project.isArchived()) {
            Instant now = clock.instant();
            List<UUID> ids = withDescendants(project);
            usageLimits.lockProjects(ids); // id TĂNG DẦN TRƯỚC khi UPDATE (không theo thứ tự nạp) — tránh deadlock với task move
            projectRepository.findAllById(ids).forEach(p -> p.archive(now));
            projectRepository.flush();
            activityService.project(userId, project, ActivityEventType.ARCHIVED); // M10 (dự án con không ghi riêng)
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
        accessPolicy.checkProjectManage(userId, project); // M8a: chỉ người quản lý (chủ / Admin / người tạo)
        UUID workspaceId = project.getWorkspace().getId();
        usageLimits.lockWorkspace(workspaceId); // chống đua (spec 8.2 #9): khoá TRƯỚC khi đếm
        refresh(project); // đọc lại sau khoá: unarchive khác song song đã khôi phục / detach xong → không kiểm trên bản cũ
        if (project.isArchived()) {
            Instant archivedAt = project.getArchivedAt();
            List<Project> restored = projectRepository.findAllById(withDescendants(project)).stream()
                    .filter(p -> archivedAt.equals(p.getArchivedAt()))
                    .toList();
            boolean detach = project.getParentId() != null && projectRepository.findById(project.getParentId())
                    .map(Project::isArchived).orElse(false);
            usageLimits.check(LimitKind.ACTIVE_PROJECTS, projectRepository.countActive(workspaceId), restored.size());
            if (detach) { // cha còn lưu trữ → thành gốc, cuối vùng chứa gốc (vùng gốc chỉ bị khoá khi phải đánh số lại)
                List<Project> rootSiblings = container(workspaceId, null);
                project.moveUnder(null, placeForInsert(restored, rootSiblings, null));
            } else {
                lockRows(restored); // id TĂNG DẦN TRƯỚC mọi UPDATE
            }
            restored.forEach(Project::unarchive);
            projectRepository.flush();
            activityService.project(userId, project, ActivityEventType.UNARCHIVED); // M10
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
        accessPolicy.checkProjectManage(userId, project); // M8a: chỉ người quản lý (chủ / Admin / người tạo)
        usageLimits.lockWorkspace(project.getWorkspace().getId()); // W trước, rồi hàng dự án id TĂNG DẦN: DB cascade xoá
        refresh(project);                                          // theo thứ tự tuỳ ý → khoá trước để không deadlock
        List<UUID> ids = withDescendants(project);                 // với archive / task move
        usageLimits.lockProjects(ids);
        favoriteService.removeAllFor(FavoriteTargetType.PROJECT, ids);
        events.publishEvent(new ProjectsDeletedEvent(ids));
        activityService.project(userId, project, ActivityEventType.DELETED); // M10 (ghi trước khi xoá: còn tên)
        projectRepository.delete(project);
        projectRepository.flush();
    }

    /**
     * M8a F8: chuyển dự án cá nhân VÀO workspace nhóm (không có chiều ngược). Chỉ chủ dự án; đích là team mình là Admin /
     * Member (người ngoài 404, Guest 403); dự án gốc không có dự án con (400); Inbox 403; lưu trữ 409; vượt giới hạn dự
     * án của team → 422. Task đổi workspace_id theo; người chuyển + cộng tác viên thành thành viên dự án (người ngoài team
     * thành GUEST). Khoá: hai workspace theo id TĂNG DẦN rồi hàng dự án.
     */
    public ProjectResponse moveToWorkspace(UUID userId, UUID projectId, MoveToWorkspaceRequest request) {
        Project project = requireReadable(userId, projectId);
        rejectInbox(project);
        accessPolicy.checkProjectManage(userId, project);
        if (project.getWorkspace().isTeam()) {
            throw ApiException.badRequest(ErrorMessage.MOVE_TO_WORKSPACE_INVALID);
        }
        accessPolicy.requireTeamEditor(userId, request.workspaceId()); // PERM: chỉ Admin của team đích
        Workspace target = workspaceService.requireWorkspace(request.workspaceId());
        if (!target.isTeam()) {
            throw ApiException.badRequest(ErrorMessage.MOVE_TO_WORKSPACE_INVALID);
        }
        if (request.folderId() != null) {
            requireFolderIn(target.getId(), request.folderId());
        }
        UUID sourceId = project.getWorkspace().getId();
        java.util.stream.Stream.of(sourceId, target.getId()).sorted().forEach(usageLimits::lockWorkspace);
        refresh(project);
        requireNotArchived(project);
        if (project.getParentId() != null || !projectTreeService.descendants(tree(sourceId), projectId).isEmpty()) {
            throw ApiException.badRequest(ErrorMessage.PROJECT_HAS_SUBPROJECTS);
        }
        usageLimits.check(LimitKind.ACTIVE_PROJECTS, projectRepository.countActive(target.getId()), 1);
        usageLimits.check(LimitKind.PROJECTS, projectRepository.countAll(target.getId()), 1);
        long sortOrder = placeForInsert(List.of(project), container(target.getId(), null), null);
        List<UUID> collaborators = projectMemberRepository.findAllByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(ProjectMember::getUserId).toList();
        project.moveToWorkspace(target, sortOrder,
                request.access() == null ? ProjectAccess.TEAM : request.access(), request.folderId(), userId);
        projectRepository.flush();
        projectRepository.moveTasksToWorkspace(projectId, target.getId(), clock.instant());
        membership.addToProject(project, userService.requireUser(userId), false);
        collaborators.forEach(id -> membership.addToWorkspace(target, userService.requireUser(id),
                com.taskly.entity.WorkspaceRole.GUEST));
        activityService.project(userId, project, ActivityEventType.UPDATED); // M10: đổi workspace / quyền truy cập
        return toResponse(userId, project);
    }

    // ---- Tra cứu cho service khác ----

    public Project requireReadable(UUID userId, UUID projectId) {
        return requireReadable(userId, projectId, ErrorMessage.PROJECT_NOT_FOUND);
    }

    /** notFoundDetail: câu 404 theo ngữ cảnh (vd tra dự án của một task → "Không tìm thấy công việc"). */
    public Project requireReadable(UUID userId, UUID projectId, ErrorMessage notFoundDetail) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> ApiException.notFound(notFoundDetail));
        accessPolicy.checkProjectRead(userId, project, notFoundDetail); // M8a: quyền theo dự án
        return project;
    }

    public Project requireWritable(UUID userId, UUID projectId) {
        return requireWritable(userId, projectId, ErrorMessage.PROJECT_NOT_FOUND);
    }

    /** Như requireReadable + dự án lưu trữ là chỉ đọc → 409 project-archived (spec 5.7). */
    public Project requireWritable(UUID userId, UUID projectId, ErrorMessage notFoundDetail) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> ApiException.notFound(notFoundDetail));
        accessPolicy.checkProjectWrite(userId, project, notFoundDetail); // M8a: 404 / xem trước → 403
        requireNotArchived(project);
        return project;
    }

    /**
     * PERM: như requireWritable nhưng chỉ đòi ĐÃ THAM GIA (đánh dấu hoàn thành / mở lại / Undo) — Member / Guest của dự
     * án nhóm được; dự án lưu trữ → 409 project-archived.
     */
    public Project requireCompletable(UUID userId, UUID projectId, ErrorMessage notFoundDetail) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> ApiException.notFound(notFoundDetail));
        accessPolicy.checkTaskComplete(userId, project, notFoundDetail);
        requireNotArchived(project);
        return project;
    }

    /** Chờ duyệt: Admin duyệt / trả lại — 404 không thấy; không phải Admin nhóm → 403 team-admin-only; dự án lưu trữ → 409. */
    public Project requireReviewable(UUID userId, UUID projectId, ErrorMessage notFoundDetail) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> ApiException.notFound(notFoundDetail));
        accessPolicy.checkReviewDecision(userId, project, notFoundDetail);
        requireNotArchived(project);
        return project;
    }

    /** Dự án đích khi tạo / chuyển task (BR-08): không thấy → 404; của mình nhưng đang lưu trữ → 422 target-archived. */
    public Project requireTarget(UUID userId, UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.PROJECT_NOT_FOUND));
        accessPolicy.checkProjectWrite(userId, project, ErrorMessage.PROJECT_NOT_FOUND); // M8a: xem trước → 403
        if (project.isArchived()) {
            throw targetArchived();
        }
        return project;
    }

    /**
     * Gọi SAU khi đã khoá dự án nguồn + đích (task move): đọc lại hai hàng từ DB — bản nạp trước khoá (cả requireTarget)
     * có thể cũ vì archive vừa commit trong lúc chờ khoá — rồi kiểm lại cờ lưu trữ: nguồn → 409 project-archived,
     * đích → 422 target-archived. Hàng đã bị xoá → 404.
     */
    public void recheckAfterLock(Project source, Project target) {
        refresh(source);
        if (target != source) {
            refresh(target);
        }
        requireNotArchived(source);
        if (target.isArchived()) {
            throw targetArchived();
        }
    }

    /**
     * Gọi SAU khi đã khoá dự án (P) của thao tác ghi dưới dự án (create task / section, reopen, duplicate, sửa / xoá /
     * reorder section, reorder task): đọc lại hàng từ DB (bản nạp trước khoá có thể cũ — archive vừa commit trong lúc
     * chờ) rồi kiểm lại cờ lưu trữ: asTarget (đích tạo task, không cha) → 422 target-archived; còn lại → 409
     * project-archived. Hàng đã bị xoá → 404.
     */
    public Project recheckAfterLock(Project project, boolean asTarget) {
        refresh(project);
        if (asTarget && project.isArchived()) {
            throw targetArchived();
        }
        requireNotArchived(project);
        return project;
    }

    /** Như recheckAfterLock(Project, false) nhưng theo id (caller chưa có thực thể): nạp rồi đọc lại. */
    public Project recheckWritableAfterLock(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.PROJECT_NOT_FOUND));
        return recheckAfterLock(project, false);
    }

    /**
     * Cổng cấu trúc cho reorder dự án (cùng quy tắc create / move / archive): khoá workspace (W), đọc lại hàng đã nạp
     * trước khoá, kiểm lại cờ lưu trữ (409). Sau lời gọi này mới được đọc anh em (container) để tính vị trí.
     */
    public void lockStructureAndRefresh(Project project) {
        usageLimits.lockWorkspace(project.getWorkspace().getId());
        refresh(project);
        requireNotArchived(project);
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

    /** Cây dự án của nhiều workspace bằng MỘT câu; workspace không có dự án → cây rỗng. */
    private Map<UUID, ProjectTreeService.Tree> trees(Collection<UUID> workspaceIds) {
        Map<UUID, List<ProjectTreeService.Node>> nodes = new HashMap<>();
        workspaceIds.forEach(id -> nodes.put(id, new ArrayList<>()));
        for (Object[] row : projectRepository.findTreeRowsIn(workspaceIds)) {
            nodes.get((UUID) row[0]).add(new ProjectTreeService.Node((UUID) row[1], (UUID) row[2], (Long) row[3]));
        }
        Map<UUID, ProjectTreeService.Tree> trees = new HashMap<>();
        nodes.forEach((id, list) -> trees.put(id, projectTreeService.tree(list)));
        return trees;
    }

    private ProjectTreeService.Tree tree(UUID workspaceId) {
        return projectTreeService.tree(projectRepository.findTreeRows(workspaceId).stream()
                .map(row -> new ProjectTreeService.Node((UUID) row[0], (UUID) row[1], (Long) row[2]))
                .toList());
    }

    /** Khoá hàng các dự án theo id TĂNG DẦN (một lần), trước khi UPDATE chúng. */
    private void lockRows(Collection<Project> projects) {
        usageLimits.lockProjects(projects.stream().map(Project::getId).toList());
    }

    /** Đọc lại hàng từ DB sau khi đã khoá (bản nạp trước khoá có thể cũ). Hàng đã bị xoá → 404. */
    private void refresh(Project project) {
        try {
            entityManager.refresh(project);
        } catch (jakarta.persistence.EntityNotFoundException e) {
            throw ApiException.notFound(ErrorMessage.PROJECT_NOT_FOUND);
        }
    }

    /** Id của dự án + mọi dự án con cháu (theo cây của workspace, gồm cả dự án lưu trữ). */
    private List<UUID> withDescendants(Project project) {
        List<UUID> ids = new ArrayList<>();
        ids.add(project.getId());
        ids.addAll(projectTreeService.descendants(tree(project.getWorkspace().getId()), project.getId()));
        return ids;
    }

    /**
     * afterId vắng (null) → CUỐI vùng chứa. siblings không gồm dự án đang đặt. Tự ghi đánh số lại. Khoá hàng (id TĂNG DẦN,
     * TRƯỚC mọi UPDATE): `mustLock` luôn (dự án sắp ghi), siblings CHỈ khi thật sự đánh số lại (placeLast không đánh số lại).
     */
    private long placeForInsert(Collection<Project> mustLock, List<Project> siblings, Optional<UUID> afterId) {
        SortOrderService.Placement placement = sortOrderService.placeAt(items(siblings), afterId);
        List<Project> toLock = new ArrayList<>(mustLock);
        if (placement.renumbers()) {
            toLock.addAll(siblings);
        }
        lockRows(toLock);
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
        // Cây dự án của từng workspace (kể cả lưu trữ). Chỉ tính cho CÁC dự án được yêu cầu + cây con của chúng (một
        // response đơn lẻ không còn đếm task mở của mọi dự án trong workspace).
        Map<UUID, Long> counts = new HashMap<>();
        Map<UUID, DescendantStats> stats = new HashMap<>();
        Map<UUID, List<Project>> byWorkspace = projects.stream()
                .collect(Collectors.groupingBy(p -> p.getWorkspace().getId()));
        // PERF: MỘT câu lấy cây của mọi workspace + MỘT câu đếm task mở cho mọi dự án cần (trước đây 2 câu MỖI workspace
        // — N+1 theo số workspace; GET projects / full sync của tài khoản 12 workspace mất ~20 câu).
        Map<UUID, ProjectTreeService.Tree> trees = trees(byWorkspace.keySet());
        Map<UUID, List<UUID>> descendantsOf = new HashMap<>();
        Set<UUID> needed = new HashSet<>();
        byWorkspace.forEach((workspaceId, requested) -> {
            ProjectTreeService.Tree tree = trees.get(workspaceId);
            for (Project project : requested) {
                if (tree.byId().containsKey(project.getId())) { // Inbox không nằm trong cây
                    List<UUID> descendants = projectTreeService.descendants(tree, project.getId());
                    descendantsOf.put(project.getId(), descendants);
                    needed.add(project.getId());
                    needed.addAll(descendants);
                }
            }
        });
        ids.stream().filter(id -> !descendantsOf.containsKey(id)).forEach(needed::add); // Inbox không nằm trong cây
        counts.putAll(countOpen(needed));
        descendantsOf.forEach((id, descendants) -> stats.put(id, new DescendantStats(descendants.size(),
                descendants.stream().mapToLong(d -> counts.getOrDefault(d, 0L)).sum())));
        Map<UUID, ProjectMapper.Sharing> sharing = sharing(userId, projects);
        return projects.stream()
                .map(p -> {
                    DescendantStats descendants = stats.getOrDefault(p.getId(), DescendantStats.NONE);
                    return ProjectMapper.toResponse(p, favorites.contains(p.getId()), counts.getOrDefault(p.getId(), 0L),
                            descendants.projects(), descendants.activeTasks(),
                            sharing.getOrDefault(p.getId(), ProjectMapper.Sharing.PRIVATE_OWNER));
                })
                .toList();
    }

    /**
     * M8a: memberCount / joined / canManage theo góc nhìn userId — 2 câu cho cả danh sách. Dự án cá nhân: chủ không có
     * hàng project_members nên memberCount = 1 + số cộng tác viên.
     */
    private Map<UUID, ProjectMapper.Sharing> sharing(UUID userId, List<Project> projects) {
        List<UUID> ids = projects.stream().map(Project::getId).toList();
        Map<UUID, AccessPolicy.ProjectAccessInfo> access = accessPolicy.projectAccess(userId, ids);
        Map<UUID, Long> members = projectMemberRepository.countByProjectIds(ids).stream()
                .collect(Collectors.toMap(ProjectMemberRepository.MemberCount::getProjectId,
                        ProjectMemberRepository.MemberCount::getMemberCount));
        Map<UUID, ProjectMapper.Sharing> result = new HashMap<>();
        for (Project project : projects) {
            AccessPolicy.ProjectAccessInfo info = access.get(project.getId());
            if (info == null) {
                continue;
            }
            long count = members.getOrDefault(project.getId(), 0L) + (project.getWorkspace().isTeam() ? 0 : 1);
            result.put(project.getId(), new ProjectMapper.Sharing(count, info.owner() || info.joined(),
                    info.canManage(), ProjectCapabilities.of(info.canEdit(), info.canComplete(), info.canReview()))); // PERM + chờ duyệt
        }
        return result;
    }
}
