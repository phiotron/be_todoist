package com.taskly.mapper;

import com.taskly.dto.section.SectionResponse;
import com.taskly.entity.Section;

public final class SectionMapper {

    private SectionMapper() {
    }

    public static SectionResponse toResponse(Section section, long activeTaskCount) {
        return new SectionResponse(section.getId(), section.getProjectId(), section.getName(), section.getDescription(),
                section.getSortOrder(), section.getArchivedAt(), activeTaskCount, section.getCreatedAt(),
                section.getUpdatedAt());
    }
}
