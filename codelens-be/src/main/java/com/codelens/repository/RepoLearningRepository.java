package com.codelens.repository;

import com.codelens.model.entity.RepoLearning;
import com.codelens.model.entity.Repository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface RepoLearningRepository extends JpaRepository<RepoLearning, UUID> {

    List<RepoLearning> findByRepository(Repository repository);

    List<RepoLearning> findByRepositoryId(UUID repositoryId);

    /**
     * Find all auto-suppressed rules for a repository.
     */
    List<RepoLearning> findByRepositoryIdAndAutoSuppressedTrue(UUID repositoryId);

    /**
     * Find a specific rule learning entry.
     */
    Optional<RepoLearning> findByRepositoryIdAndRuleIdAndAnalyzer(
            UUID repositoryId, String ruleId, String analyzer);

    /**
     * Find all rules that should be suppressed (either auto-suppressed or meeting threshold).
     */
    @Query("SELECT rl FROM RepoLearning rl WHERE rl.repository.id = :repoId " +
           "AND (rl.autoSuppressed = true OR rl.falsePositiveCount >= :threshold)")
    List<RepoLearning> findSuppressedRules(
            @Param("repoId") UUID repositoryId,
            @Param("threshold") int falsePositiveThreshold);

    /**
     * Find rules with severity override for a repository.
     */
    List<RepoLearning> findByRepositoryIdAndSeverityOverrideNotNull(UUID repositoryId);

    /**
     * Count total feedback received for a repository.
     */
    @Query("SELECT SUM(rl.falsePositiveCount + rl.notHelpfulCount) FROM RepoLearning rl " +
           "WHERE rl.repository.id = :repoId")
    Long countTotalFeedback(@Param("repoId") UUID repositoryId);

    /**
     * Delete all learning data for a repository.
     */
    void deleteByRepositoryId(UUID repositoryId);
}
