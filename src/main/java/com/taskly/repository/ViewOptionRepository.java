package com.taskly.repository;

import com.taskly.entity.ViewOption;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ViewOptionRepository extends JpaRepository<ViewOption, UUID> {

    List<ViewOption> findByUserIdOrderByViewKeyAsc(UUID userId);

    Optional<ViewOption> findByUserIdAndViewKey(UUID userId, String viewKey);

    @Transactional
    @Modifying
    @Query("delete from ViewOption v where v.userId = :userId and v.viewKey = :viewKey")
    int deleteView(@Param("userId") UUID userId, @Param("viewKey") String viewKey);

    /** Xoá Display theo khoá view_key của MỌI user: ViewOptionsService gọi khi xoá dự án / label / filter (Task 17). */
    @Transactional
    @Modifying
    @Query("delete from ViewOption v where v.viewKey in :viewKeys")
    int deleteViews(@Param("viewKeys") Collection<String> viewKeys);
}
