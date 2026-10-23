package com.taskly.service;

import com.taskly.entity.Project;
import com.taskly.entity.ViewStyle;
import com.taskly.entity.Workspace;
import com.taskly.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sở hữu bảng projects. M1 sẽ thêm CRUD project. */
@Service
public class ProjectService {

    public static final String INBOX_NAME = "Inbox";

    private final ProjectRepository projectRepository;

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    /** Inbox của workspace cá nhân: is_inbox = true, layout LIST, đứng đầu (sort_order 0), chưa có màu. */
    @Transactional
    public Project createInbox(Workspace personalWorkspace) {
        return projectRepository.save(new Project(personalWorkspace, INBOX_NAME, null, true, ViewStyle.LIST, 0));
    }
}
