package com.taskly.service;

import com.taskly.dto.location.LocationReminderResponse;
import com.taskly.repository.LocationReminderQuery;
import com.taskly.security.AccessPolicy;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Danh sách nhắc theo vị trí cho app đăng ký geofence (M12). Như ReminderOccurrenceService: không lọc theo
 * remindersEnabled — app áp dụng cờ đó.
 */
@Service
@Transactional(readOnly = true)
public class LocationReminderService {

    /** Android cho tối đa 100 geofence / app. */
    public static final int MAX_GEOFENCES = 100;

    private final LocationReminderQuery locationReminderQuery;
    private final AccessPolicy accessPolicy;

    public LocationReminderService(LocationReminderQuery locationReminderQuery, AccessPolicy accessPolicy) {
        this.locationReminderQuery = locationReminderQuery;
        this.accessPolicy = accessPolicy;
    }

    public List<LocationReminderResponse> list(UUID userId) {
        return locationReminderQuery.find(userId, accessPolicy.listScope(userId), MAX_GEOFENCES);
    }
}
