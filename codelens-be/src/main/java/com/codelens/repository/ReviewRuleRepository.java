package com.codelens.repository;

import com.codelens.model.entity.ReviewRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewRuleRepository extends JpaRepository<ReviewRule, UUID> {

    List<ReviewRule> findByOrganizationIdAndEnabledTrue(UUID organizationId);

    List<ReviewRule> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<ReviewRule> findByOrganizationIdIsNullAndEnabledTrue();

    List<ReviewRule> findByOrganizationIdIsNullOrderByCreatedAtDesc();
}
