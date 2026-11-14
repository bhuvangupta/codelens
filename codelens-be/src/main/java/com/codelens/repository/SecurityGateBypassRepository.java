package com.codelens.repository;

import com.codelens.model.entity.Repository.GitProvider;
import com.codelens.model.entity.SecurityGateBypass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SecurityGateBypassRepository extends JpaRepository<SecurityGateBypass, UUID> {

    Optional<SecurityGateBypass> findFirstByProviderAndRepoFullNameAndHeadShaOrderByCreatedAtDesc(
        GitProvider provider, String repoFullName, String headSha);
}
