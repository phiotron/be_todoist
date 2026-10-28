package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.location.CreateLocationRequest;
import com.taskly.dto.location.LocationResponse;
import com.taskly.entity.LimitKind;
import com.taskly.entity.Location;
import com.taskly.mapper.LocationMapper;
import com.taskly.repository.LocationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Địa điểm đã lưu cho nhắc theo vị trí (M12). Địa điểm thuộc người tạo: của người khác / không có → 404 (không lộ là
 * có tồn tại). Giới hạn LOCATIONS qua UsageLimits, khoá hàng users trước khi đếm (chống đua như M2 / M4).
 */
@Service
@Transactional
public class LocationService {

    private final LocationRepository locationRepository;
    private final UsageLimits usageLimits;

    public LocationService(LocationRepository locationRepository, UsageLimits usageLimits) {
        this.locationRepository = locationRepository;
        this.usageLimits = usageLimits;
    }

    @Transactional(readOnly = true)
    public List<LocationResponse> list(UUID userId) {
        return locationRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId).stream()
                .map(LocationMapper::toResponse)
                .toList();
    }

    public LocationResponse create(UUID userId, CreateLocationRequest request) {
        usageLimits.lockUser(userId);
        usageLimits.check(LimitKind.LOCATIONS, locationRepository.countByUserId(userId), 1);
        Location location = new Location(userId, request.name(), request.latitude(), request.longitude(),
                request.radius());
        return LocationMapper.toResponse(locationRepository.save(location));
    }

    /** Xoá địa điểm; DB xoá luôn các nhắc LOCATION dùng nó (fk_reminders_location ON DELETE CASCADE). */
    public void delete(UUID userId, UUID locationId) {
        locationRepository.delete(requireOwned(userId, locationId));
    }

    /** Địa điểm của chính user; không có / của người khác → 404 error.location-not-found. */
    @Transactional(readOnly = true)
    public Location requireOwned(UUID userId, UUID locationId) {
        return locationRepository.findByIdAndUserId(locationId, userId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.LOCATION_NOT_FOUND));
    }
}
