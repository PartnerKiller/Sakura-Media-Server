package com.sakuradata.media.repository;

import com.sakuradata.media.model.FirewallRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FirewallRuleRepository extends JpaRepository<FirewallRule, Long> {
}
