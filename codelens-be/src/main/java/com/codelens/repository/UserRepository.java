package com.codelens.repository;

import com.codelens.model.entity.Organization;
import com.codelens.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByProviderAndProviderId(String provider, String providerId);

    boolean existsByEmail(String email);

    List<User> findByOrganizationId(UUID organizationId);

    Optional<User> findByGithubUsername(String githubUsername);

    Optional<User> findByGitlabUsername(String gitlabUsername);

    /**
     * Find organization through user's reviews (for backfill when user has no org set).
     * Returns the first organization found from the user's reviews.
     * This is more efficient than loading all reviews and iterating.
     */
    @Query("SELECT DISTINCT r.repository.organization FROM Review r WHERE r.user.id = :userId AND r.repository.organization IS NOT NULL")
    List<Organization> findOrganizationsFromReviews(@Param("userId") UUID userId);
}
