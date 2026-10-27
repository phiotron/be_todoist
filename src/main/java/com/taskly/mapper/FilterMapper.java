package com.taskly.mapper;

import com.taskly.dto.filter.FilterResponse;
import com.taskly.entity.Filter;

public final class FilterMapper {

    private FilterMapper() {
    }

    public static FilterResponse toResponse(Filter filter, boolean favorite) {
        return new FilterResponse(filter.getId(), filter.getName(), filter.getQuery(), filter.getColor(), favorite,
                filter.getSortOrder());
    }
}
