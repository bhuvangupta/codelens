package com.codelens.service;

import com.codelens.model.entity.Organization;
import com.codelens.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;

    /**
     * Find an organization by name, or create it if it doesn't exist.
     * Used during OAuth login to auto-assign users to organizations based on email domain.
     *
     * @param orgName The organization name
     * @param domain The email domain (used to generate slug)
     * @return The existing or newly created organization
     */
    @Transactional
    public Organization findOrCreateByName(String orgName, String domain) {
        return organizationRepository.findByName(orgName)
                .orElseGet(() -> {
                    log.info("Creating new organization: {} for domain: {}", orgName, domain);
                    Organization org = Organization.builder()
                            .name(orgName)
                            .slug(domain.replace(".", "-"))
                            .autoApproveMembers(true)
                            .build();
                    return organizationRepository.save(org);
                });
    }
}
