package com.taskly.mapper;

import com.taskly.dto.location.LocationResponse;
import com.taskly.entity.Location;

public final class LocationMapper {

    private LocationMapper() {
    }

    public static LocationResponse toResponse(Location location) {
        return new LocationResponse(location.getId(), location.getName(), location.getLatitude(),
                location.getLongitude(), location.getRadius(), location.getCreatedAt());
    }
}
