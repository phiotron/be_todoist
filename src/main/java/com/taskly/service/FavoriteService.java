package com.taskly.service;

import com.taskly.dto.favorite.FavoriteResponse;
import com.taskly.entity.FavoriteTargetType;
import com.taskly.repository.FavoriteRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sở hữu bảng favorites. Kiểm quyền với đích do service gọi (ProjectService; M2 Label/FilterService) làm trước. */
@Service
@Transactional
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final Clock clock;

    public FavoriteService(FavoriteRepository favoriteRepository, Clock clock) {
        this.favoriteRepository = favoriteRepository;
        this.clock = clock;
    }

    public void add(UUID userId, FavoriteTargetType type, UUID targetId) {
        favoriteRepository.insertIgnoringDuplicate(UUID.randomUUID(), userId, type.name(), targetId, clock.instant());
    }

    public void remove(UUID userId, FavoriteTargetType type, UUID targetId) {
        favoriteRepository.deleteOne(userId, type, targetId);
    }

    /** Khi đích bị xoá vĩnh viễn: xoá yêu thích của MỌI user trỏ tới các đích này (spec 5.7, 8.2.8). */
    public void removeAllFor(FavoriteTargetType type, Collection<UUID> targetIds) {
        if (!targetIds.isEmpty()) {
            favoriteRepository.deleteByTargets(type, targetIds);
        }
    }

    @Transactional(readOnly = true)
    public Set<UUID> favoriteIds(UUID userId, FavoriteTargetType type, Collection<UUID> targetIds) {
        if (targetIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(favoriteRepository.findTargetIds(userId, type, targetIds));
    }

    @Transactional(readOnly = true)
    public boolean isFavorite(UUID userId, FavoriteTargetType type, UUID targetId) {
        return favoriteIds(userId, type, Set.of(targetId)).contains(targetId);
    }

    /** GET favorites: theo thứ tự thêm (cũ trước), bỏ dự án lưu trữ. */
    @Transactional(readOnly = true)
    public List<FavoriteResponse> list(UUID userId) {
        return favoriteRepository.findVisible(userId).stream()
                .map(f -> new FavoriteResponse(f.getTargetType(), f.getTargetId(), f.getCreatedAt()))
                .toList();
    }
}
