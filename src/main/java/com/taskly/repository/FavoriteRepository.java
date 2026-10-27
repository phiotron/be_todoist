package com.taskly.repository;

import com.taskly.entity.Favorite;
import com.taskly.entity.FavoriteTargetType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FavoriteRepository extends JpaRepository<Favorite, UUID> {

    /** Idempotent và an toàn khi hai request chạy song song (ON CONFLICT DO NOTHING). */
    @Modifying(flushAutomatically = true)
    @Query(value = "insert into favorites (id, user_id, target_type, target_id, created_at, updated_at) "
            + "values (:id, :userId, :targetType, :targetId, :now, :now) "
            + "on conflict (user_id, target_type, target_id) do nothing", nativeQuery = true)
    int insertIgnoringDuplicate(UUID id, UUID userId, String targetType, UUID targetId, Instant now);

    @Modifying(flushAutomatically = true)
    @Query("delete from Favorite f where f.userId = :userId and f.targetType = :targetType and f.targetId = :targetId")
    int deleteOne(UUID userId, FavoriteTargetType targetType, UUID targetId);

    /** Xoá mọi yêu thích (của MỌI user) trỏ tới các đích sắp bị xoá vĩnh viễn (spec 5.7). */
    @Modifying(flushAutomatically = true)
    @Query("delete from Favorite f where f.targetType = :targetType and f.targetId in :targetIds")
    int deleteByTargets(FavoriteTargetType targetType, Collection<UUID> targetIds);

    @Query("select f.targetId from Favorite f "
            + "where f.userId = :userId and f.targetType = :targetType and f.targetId in :targetIds")
    List<UUID> findTargetIds(UUID userId, FavoriteTargetType targetType, Collection<UUID> targetIds);

    /**
     * Khối Favorites: theo thứ tự thêm, bỏ dự án lưu trữ (giữ dòng). Đọc projects.archived_at là truy vấn chỉ đọc
     * có chủ đích (Lệch #4 của plan M1). LABEL / FILTER (M2) đi qua nguyên vẹn.
     */
    @Query(value = "select f.* from favorites f where f.user_id = :userId and (f.target_type <> 'PROJECT' "
            + "or exists (select 1 from projects p where p.id = f.target_id and p.archived_at is null)) "
            + "order by f.created_at, f.id", nativeQuery = true)
    List<Favorite> findVisible(UUID userId);
}
