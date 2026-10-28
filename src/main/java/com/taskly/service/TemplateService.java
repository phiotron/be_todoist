package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.dto.project.ProjectResponse;
import com.taskly.dto.template.ApplyTemplateRequest;
import com.taskly.dto.template.LibraryTemplateResponse;
import com.taskly.dto.template.SaveTemplateRequest;
import com.taskly.dto.template.TemplateDetailResponse;
import com.taskly.dto.template.TemplateResponse;
import com.taskly.entity.LimitKind;
import com.taskly.entity.Project;
import com.taskly.entity.ProjectColor;
import com.taskly.entity.Template;
import com.taskly.entity.ViewStyle;
import com.taskly.entity.Workspace;
import com.taskly.repository.ProjectRepository;
import com.taskly.repository.TaskRepository;
import com.taskly.repository.SectionRepository;
import com.taskly.repository.TemplateRepository;
import com.taskly.security.AccessPolicy;
import com.taskly.service.content.ProjectContent;
import com.taskly.service.content.ProjectContentReader;
import com.taskly.service.content.ProjectContentWriter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M11 F5–F7: Save as template (≤ 100 / người, LimitKind.TEMPLATES — khoá hàng users rồi đếm), Browse (của mình + thư viện
 * TemplateLibrary), Apply (dự án gốc mới ở cuối vùng gốc, hoặc thêm vào cuối một dự án có sẵn). Template không sửa được.
 */
@Service
@Transactional
public class TemplateService {

    /** Kết quả áp template: created = dự án mới (201), ngược lại thêm vào dự án có sẵn (200). */
    public record ApplyResult(ProjectResponse project, boolean created) {
    }

    private final TemplateRepository templateRepository;
    private final TemplateLibrary library;
    private final ProjectService projectService;
    private final ProjectRepository projectRepository;
    private final SectionRepository sectionRepository;
    private final TaskRepository taskRepository;
    private final WorkspaceService workspaceService;
    private final AccessPolicy accessPolicy;
    private final SortOrderService sortOrderService;
    private final UsageLimits usageLimits;
    private final ProjectContentReader contentReader;
    private final ProjectContentWriter contentWriter;

    public TemplateService(TemplateRepository templateRepository, TemplateLibrary library,
                           ProjectService projectService, ProjectRepository projectRepository,
                           SectionRepository sectionRepository, TaskRepository taskRepository,
                           WorkspaceService workspaceService, AccessPolicy accessPolicy,
                           SortOrderService sortOrderService, UsageLimits usageLimits,
                           ProjectContentReader contentReader, ProjectContentWriter contentWriter) {
        this.templateRepository = templateRepository;
        this.library = library;
        this.projectService = projectService;
        this.projectRepository = projectRepository;
        this.sectionRepository = sectionRepository;
        this.taskRepository = taskRepository;
        this.workspaceService = workspaceService;
        this.accessPolicy = accessPolicy;
        this.sortOrderService = sortOrderService;
        this.usageLimits = usageLimits;
        this.contentReader = contentReader;
        this.contentWriter = contentWriter;
    }

    // ---- My templates ----

    @Transactional(readOnly = true)
    public List<TemplateResponse> list(UUID userId) {
        return templateRepository.findByUserIdOrderByCreatedAtDescIdAsc(userId).stream()
                .map(TemplateService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public TemplateDetailResponse get(UUID userId, UUID templateId) {
        Template template = requireMine(userId, templateId);
        TemplateBody body = TemplateBody.fromJson(template.getContent());
        return new TemplateDetailResponse(template.getId().toString(), TemplateDetailResponse.MINE, null,
                template.getName(), template.getDescription(), template.getColor(), body.sections().size(),
                body.taskCount(), body.tasks(), body.sections());
    }

    /** Save as template: Inbox → 403, dự án lưu trữ → 409, mẫu thứ 101 → 422 TEMPLATES. */
    public TemplateResponse save(UUID userId, SaveTemplateRequest request) {
        Project project = projectService.requireReadable(userId, request.projectId());
        if (project.isInbox()) {
            throw ApiException.forbidden(ErrorMessage.INBOX_PROTECTED);
        }
        accessPolicy.checkWrite(userId, project.getWorkspace().getId(), ErrorMessage.PROJECT_NOT_FOUND);
        if (project.isArchived()) {
            throw ApiException.conflict(ErrorMessage.PROJECT_ARCHIVED).withCode(ProblemCodes.PROJECT_ARCHIVED);
        }
        usageLimits.lockUser(userId); // chống đua (spec 8.2 #9): khoá hàng cha TRƯỚC khi đếm
        usageLimits.check(LimitKind.TEMPLATES, templateRepository.countByUserId(userId), 1);
        TemplateBody body = TemplateBody.of(contentReader.readProject(project.getId()));
        Template template = new Template(userId,
                request.name() != null ? request.name() : project.getName(),
                request.description() != null ? request.description() : project.getDescription(),
                project.getColor().name(), body.toJson(), body.sections().size(), body.taskCount());
        templateRepository.saveAndFlush(template);
        return toResponse(template);
    }

    public void delete(UUID userId, UUID templateId) {
        templateRepository.delete(requireMine(userId, templateId));
        templateRepository.flush();
    }

    public ApplyResult applyMine(UUID userId, UUID templateId, ApplyTemplateRequest request) {
        Template template = requireMine(userId, templateId);
        return apply(userId, template.getName(), colorOf(template.getColor()),
                TemplateBody.fromJson(template.getContent()), request);
    }

    // ---- Thư viện ----

    @Transactional(readOnly = true)
    public List<LibraryTemplateResponse> library(Locale locale) {
        return library.entries(locale).stream()
                .map(e -> new LibraryTemplateResponse(e.key(), e.category(), e.name(), e.description(), e.color(),
                        e.sectionCount(), e.taskCount()))
                .toList();
    }

    @Transactional(readOnly = true)
    public TemplateDetailResponse libraryDetail(String key, Locale locale) {
        TemplateLibrary.Entry entry = library.require(key, locale);
        return new TemplateDetailResponse(entry.key(), TemplateDetailResponse.LIBRARY, entry.category(), entry.name(),
                entry.description(), entry.color(), entry.sectionCount(), entry.taskCount(), entry.body().tasks(),
                entry.body().sections());
    }

    public ApplyResult applyLibrary(UUID userId, String key, ApplyTemplateRequest request, Locale locale) {
        TemplateLibrary.Entry entry = library.require(key, locale);
        return apply(userId, entry.name(), colorOf(entry.color()), entry.body(), request);
    }

    // ---- nội bộ ----

    private ApplyResult apply(UUID userId, String templateName, ProjectColor templateColor, TemplateBody body,
                              ApplyTemplateRequest request) {
        ProjectContent content = body.toContent();
        if (request.projectId() == null) {
            return new ApplyResult(applyToNewProject(userId, templateName, templateColor, content, request), true);
        }
        Project project = projectService.requireWritable(userId, request.projectId()); // 404 / 409 project-archived
        usageLimits.lockProject(project.getId());
        projectService.recheckAfterLock(project, false);
        int sections = content.sections().size();
        usageLimits.check(LimitKind.PROJECT_SECTIONS,
                sectionRepository.countByProjectIdAndArchivedAtIsNull(project.getId()), sections);
        usageLimits.check(LimitKind.PROJECT_SECTIONS_TOTAL, sectionRepository.countByProjectId(project.getId()), sections);
        usageLimits.check(LimitKind.PROJECT_ACTIVE_TASKS, taskRepository.countOpenInProject(project.getId()),
                content.taskCount());
        contentWriter.append(project, content, userId);
        projectRepository.flush();
        return new ApplyResult(projectService.get(userId, project.getId()), false);
    }

    /** Dự án gốc mới ở CUỐI vùng gốc (khoá W trước khi đếm, như POST projects). */
    private ProjectResponse applyToNewProject(UUID userId, String templateName, ProjectColor templateColor,
                                              ProjectContent content, ApplyTemplateRequest request) {
        Workspace workspace = workspaceService.personalWorkspace(userId);
        usageLimits.lockWorkspace(workspace.getId());
        usageLimits.check(LimitKind.ACTIVE_PROJECTS, projectRepository.countActive(workspace.getId()), 1);
        usageLimits.check(LimitKind.PROJECTS, projectRepository.countAll(workspace.getId()), 1);
        usageLimits.check(LimitKind.PROJECT_SECTIONS, 0, content.sections().size());
        usageLimits.check(LimitKind.PROJECT_SECTIONS_TOTAL, 0, content.sections().size());
        usageLimits.check(LimitKind.PROJECT_ACTIVE_TASKS, 0, content.taskCount());
        List<Project> roots = projectService.container(workspace.getId(), null);
        long sortOrder = sortOrderService.placeLast(roots.stream()
                .map(p -> new SortOrderService.Item(p.getId(), p.getSortOrder())).toList()).sortOrder(); // cuối: không đánh số lại
        String name = request.name() != null ? request.name()
                : ProjectDuplicateService.truncate(templateName, ProjectDuplicateService.MAX_NAME);
        Project project = new Project(workspace, null, name, "",
                request.color() != null ? request.color() : templateColor, false, ViewStyle.LIST, sortOrder);
        projectRepository.save(project);
        contentWriter.append(project, content, userId);
        projectRepository.flush();
        return projectService.get(userId, project.getId());
    }

    private Template requireMine(UUID userId, UUID templateId) {
        return templateRepository.findByIdAndUserId(templateId, userId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.TEMPLATE_NOT_FOUND));
    }

    private static ProjectColor colorOf(String key) {
        try {
            return ProjectColor.valueOf(key);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ProjectColor.charcoal;
        }
    }

    private static TemplateResponse toResponse(Template template) {
        return new TemplateResponse(template.getId(), template.getName(), template.getDescription(),
                template.getColor(), template.getSectionCount(), template.getTaskCount(), template.getCreatedAt(),
                template.getUpdatedAt());
    }
}
