package com.sakuradata.media.repository;

import com.sakuradata.media.model.StorageRoot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StorageRootRepository extends JpaRepository<StorageRoot, Long> {
    List<StorageRoot> findByEnabledTrueOrderByOrderIndexAsc();
    List<StorageRoot> findAllByOrderByOrderIndexAsc();
    Optional<StorageRoot> findByPath(String path);
    Optional<StorageRoot> findByName(String name);
    boolean existsByPath(String path);
    boolean existsByName(String name);
}
