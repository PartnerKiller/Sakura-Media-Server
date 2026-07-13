package com.sakuradata.media.repository;

import com.sakuradata.media.model.CronJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CronJobRepository extends JpaRepository<CronJob, Long> {
}
