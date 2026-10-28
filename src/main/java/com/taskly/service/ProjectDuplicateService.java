package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.dto.project.ProjectResponse;
import com.taskly.entity.LimitKind;
import com.taskly.entity.Project;
import com.taskly.repository.ProjectRepository;
import com.taskly.security.AccessPolicy;
import com.taskly.service.content.ProjectContent;
import com.taskly.service.content.ProjectContentReader;
import com.taskly.service.content.ProjectContentWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M11 F1 — nhân bản dự án ("Copy of …" / "Bản sao của …" theo ngôn ngữ của request): anh em của bản gốc, đặt NGAY SAU
 * bản gốc; giữ màu / mô tả / view style; chép section đang dùng + task đang mở (cây con đang mở, label); KHÔNG chép dự án
 * con, yêu thích, nhắc, task đã xong, section lưu trữ. Khoá theo quy tắc cấu trúc dự án: W (workspace) → đọc lại → khoá
 * hàng dự án (gốc + anh em khi đánh số lại) theo id tăng dần → đếm ACTIVE_PROJECTS / PROJECTS.
 */
@Service
@Transactional
public class ProjectDuplicateService {

    static final String COPY_NAME_KEY = "m11.copy-of";
    static final int MAX_NAME = 120;

    private final ProjectService projectService;
    private final ProjectRepository projectRepository;
    private final AccessPolicy accessPolicy;
    private final SortOrderService sortOrderService;
    private final UsageLimits usageLimits;
    private final ProjectContentReader contentReader;
    private final ProjectContentWriter contentWriter;
    private final MessageSource messageSource;

    public ProjectDuplicateService(ProjectService projectService, ProjectRepository projectRepository,
                                   AccessPolicy accessPolicy, SortOrderService sortOrderService,
                                   UsageLimits usageLimits, ProjectContentReader contentReader,
                                   ProjectContentWriter contentWriter, MessageSource messageSource) {
        this.projectService = projectService;
        this.projectRepository = projectRepository;
        this.accessPolicy = accessPolicy;
        this.sortOrderService = sortOrderService;
        this.usageLimits = usageLimits;
        this.contentReader = contentReader;
        this.contentWriter = contentWriter;
        this.messageSource = messageSource;
    }

    public ProjectResponse duplicate(UUID userId, UUID projectId, Locale locale) {
        Project source = projectService.requireReadable(userId, projectId);
        if (source.isInbox()) {
            throw ApiException.forbidden(ErrorMessage.INBOX_PROTECTED);
        }
        accessPolicy.checkWrite(userId, source.getWorkspace().getId(), ErrorMessage.PROJECT_NOT_FOUND);
        if (source.isArchived()) {
            throw ApiException.conflict(ErrorMessage.PROJECT_ARCHIVED).withCode(ProblemCodes.PROJECT_ARCHIVED);
        }
        projectService.lockStructureAndRefresh(source); // W → đọc lại → 409 nếu vừa bị lưu trữ
        UUID workspaceId = source.getWorkspace().getId();
        usageLimits.check(LimitKind.ACTIVE_PROJECTS, projectRepository.countActive(workspaceId), 1);
        usageLimits.check(LimitKind.PROJECTS, projectRepository.countAll(workspaceId), 1);

        List<Project> siblings = projectService.container(workspaceId, source.getParentId());
        SortOrderService.Placement placement = sortOrderService.placeAfter(siblings.stream()
                .map(p -> new SortOrderService.Item(p.getId(), p.getSortOrder())).toList(), source.getId());
        List<UUID> toLock = new ArrayList<>(List.of(source.getId())); // khoá bản gốc: đọc nội dung ổn định
        if (placement.renumbers()) {
            siblings.forEach(p -> toLock.add(p.getId()));
        }
        usageLimits.lockProjects(toLock);
        if (placement.renumbers()) {
            siblings.forEach(p -> p.setSortOrder(placement.renumbered().get(p.getId())));
        }

        ProjectContent content = contentReader.readProject(source.getId());
        usageLimits.check(LimitKind.PROJECT_SECTIONS, 0, content.sections().size());
        usageLimits.check(LimitKind.PROJECT_SECTIONS_TOTAL, 0, content.sections().size());
        usageLimits.check(LimitKind.PROJECT_ACTIVE_TASKS, 0, content.taskCount());

        Project copy = new Project(source.getWorkspace(), source.getParentId(), copyName(source.getName(), locale),
                source.getDescription(), source.getColor(), false, source.getViewStyle(), placement.sortOrder());
        projectRepository.save(copy);
        contentWriter.append(copy, content, userId);
        projectRepository.flush();
        return projectService.get(userId, copy.getId());
    }

    /** "Copy of X" theo ngôn ngữ; cắt còn 120 ký tự (đếm theo code point như VARCHAR của Postgres). */
    String copyName(String name, Locale locale) {
        return truncate(messageSource.getMessage(COPY_NAME_KEY, new Object[] {name}, locale), MAX_NAME);
    }

    static String truncate(String value, int max) {
        if (value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }
}
