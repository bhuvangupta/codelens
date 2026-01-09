package com.codelens.repository;

import com.codelens.model.entity.RepoPromptHint;
import com.codelens.model.entity.Repository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface RepoPromptHintRepository extends JpaRepository<RepoPromptHint, UUID> {

    List<RepoPromptHint> findByRepository(Repository repository);

    List<RepoPromptHint> findByRepositoryId(UUID repositoryId);

    /**
     * Find all active hints for a repository.
     */
    List<RepoPromptHint> findByRepositoryIdAndActiveTrue(UUID repositoryId);

    /**
     * Find hints by source type.
     */
    List<RepoPromptHint> findByRepositoryIdAndSource(UUID repositoryId, RepoPromptHint.Source source);

    /**
     * Find active user-added hints for a repository.
     */
    List<RepoPromptHint> findByRepositoryIdAndActiveTrueAndSource(
            UUID repositoryId, RepoPromptHint.Source source);

    /**
     * Delete all hints for a repository.
     */
    void deleteByRepositoryId(UUID repositoryId);
}
