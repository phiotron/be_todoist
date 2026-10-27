package com.taskly.mapper;

import com.taskly.dto.label.LabelResponse;
import com.taskly.entity.Label;

public final class LabelMapper {

    private LabelMapper() {
    }

    public static LabelResponse toResponse(Label label, boolean favorite) {
        return new LabelResponse(label.getId(), label.getName(), label.getColor(), favorite, label.getSortOrder());
    }
}
