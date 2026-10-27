package com.taskly.repository;

import com.taskly.entity.Favorite;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Truy vấn yêu thích thêm ở Task 12. */
public interface FavoriteRepository extends JpaRepository<Favorite, UUID> {
}
