package com.codelens.repository;

import com.codelens.model.entity.MembershipRequest;
import com.codelens.model.entity.Organization;
import com.codelens.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MembershipRequestRepository extends JpaRepository<MembershipRequest, UUID> {

    Optional<MembershipRequest> findByUserAndOrganization(User user, Organization organization);

    List<MembershipRequest> findByOrganizationAndStatus(Organization organization, MembershipRequest.Status status);

    long countByOrganizationAndStatus(Organization organization, MembershipRequest.Status status);

    Optional<MembershipRequest> findByUserAndStatus(User user, MembershipRequest.Status status);

    List<MembershipRequest> findByUser(User user);
}
