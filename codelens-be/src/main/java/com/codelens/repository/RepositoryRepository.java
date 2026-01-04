package com.codelens.repository;

import com.codelens.model.entity.Repository;
import com.codelens.model.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface RepositoryRepository extends JpaRepository<Repository, UUID> {

    List<Repository> findByOrganization(Organization organization);

    Optional<Repository> findByFullNameAndProvider(String fullName, Repository.GitProvider provider);

    Optional<Repository> findByProviderRepoIdAndProvider(String providerRepoId, Repository.GitProvider provider);

    List<Repository> findByOrganizationAndAutoReviewEnabledTrue(Organization organization);
}
