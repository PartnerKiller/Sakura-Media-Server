package com.sakuradata.media.repository;

import com.sakuradata.media.model.ShareLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShareLinkRepository extends JpaRepository<ShareLink, Long> {

    Optional<ShareLink> findByCode(String code);

    Optional<ShareLink> findByFilePathAndUserIdAndIsActiveTrue(String filePath, Long userId);

    List<ShareLink> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<ShareLink> findAllByOrderByCreatedAtDesc();

    void deleteByCode(String code);
}
