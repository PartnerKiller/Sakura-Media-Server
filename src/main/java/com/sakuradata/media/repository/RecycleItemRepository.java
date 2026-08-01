package com.sakuradata.media.repository;

import com.sakuradata.media.model.RecycleItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RecycleItemRepository extends JpaRepository<RecycleItem, Long> {
    List<RecycleItem> findByUserId(Long userId);
    List<RecycleItem> findByDeletedAtBefore(LocalDateTime time);
}
