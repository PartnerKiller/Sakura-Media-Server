package com.sakuradata.media.repository;

import com.sakuradata.media.model.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {
    List<Permission> findByUserId(Long userId);

    @Transactional
    void deleteByUserId(Long userId);
}
