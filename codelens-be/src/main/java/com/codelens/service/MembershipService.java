package com.codelens.service;

import com.codelens.model.entity.MembershipRequest;
import com.codelens.model.entity.Organization;
import com.codelens.model.entity.User;
import com.codelens.repository.MembershipRequestRepository;
import com.codelens.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MembershipService {

    private final MembershipRequestRepository membershipRequestRepository;
    private final UserRepository userRepository;

    /**
     * Request membership for a user to an organization.
     * If org has autoApproveMembers=true, immediately joins. Otherwise creates pending request.
     *
     * @return true if user was auto-approved and joined, false if pending approval
     */
    @Transactional
    public boolean requestMembership(User user, Organization organization) {
        // Check if user already belongs to this organization
        if (user.getOrganization() != null && user.getOrganization().getId().equals(organization.getId())) {
            log.debug("User {} already belongs to organization {}", user.getEmail(), organization.getName());
            return true;
        }

        // Check if there's already a pending request
        Optional<MembershipRequest> existingRequest = membershipRequestRepository.findByUserAndOrganization(user, organization);
        if (existingRequest.isPresent()) {
            MembershipRequest request = existingRequest.get();
            if (request.getStatus() == MembershipRequest.Status.PENDING) {
                log.debug("User {} already has pending request to join {}", user.getEmail(), organization.getName());
                return false;
            } else if (request.getStatus() == MembershipRequest.Status.APPROVED) {
                // Already approved, shouldn't happen but handle it
                return true;
            }
            // If rejected, allow them to request again by updating the existing request
        }

        // Check if auto-approve is enabled
        if (Boolean.TRUE.equals(organization.getAutoApproveMembers())) {
            // Auto-approve: directly add user to organization
            user.setOrganization(organization);
            userRepository.save(user);
            log.info("Auto-approved user {} to join organization {}", user.getEmail(), organization.getName());

            // Create an approved request record for audit trail
            MembershipRequest request = existingRequest.orElse(MembershipRequest.builder()
                    .user(user)
                    .organization(organization)
                    .build());
            request.setStatus(MembershipRequest.Status.APPROVED);
            request.setReviewedAt(LocalDateTime.now());
            membershipRequestRepository.save(request);

            return true;
        }

        // Create or update pending request
        MembershipRequest request = existingRequest.orElse(MembershipRequest.builder()
                .user(user)
                .organization(organization)
                .build());
        request.setStatus(MembershipRequest.Status.PENDING);
        request.setReviewedAt(null);
        request.setReviewedBy(null);
        membershipRequestRepository.save(request);

        log.info("Created pending membership request for user {} to join organization {}",
                user.getEmail(), organization.getName());
        return false;
    }

    /**
     * Approve a membership request (admin only)
     */
    @Transactional
    public MembershipRequest approveMembership(UUID requestId, User admin) {
        // SECURITY: Verify admin has ADMIN role
        if (admin.getRole() != User.UserRole.ADMIN) {
            log.warn("Non-admin user {} attempted to approve membership request {}",
                    admin.getEmail(), requestId);
            throw new SecurityException("Only administrators can approve membership requests");
        }

        MembershipRequest request = membershipRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Membership request not found: " + requestId));

        // SECURITY: Verify admin belongs to the same organization as the request
        if (admin.getOrganization() == null ||
                !admin.getOrganization().getId().equals(request.getOrganization().getId())) {
            log.warn("Admin {} from different org attempted to approve request {} for org {}",
                    admin.getEmail(), requestId, request.getOrganization().getName());
            throw new SecurityException("Can only approve membership requests for your own organization");
        }

        if (request.getStatus() != MembershipRequest.Status.PENDING) {
            throw new IllegalStateException("Request is not pending, current status: " + request.getStatus());
        }

        // Add user to organization
        User user = request.getUser();
        user.setOrganization(request.getOrganization());
        userRepository.save(user);

        // Update request
        request.setStatus(MembershipRequest.Status.APPROVED);
        request.setReviewedAt(LocalDateTime.now());
        request.setReviewedBy(admin);
        membershipRequestRepository.save(request);

        log.info("Admin {} approved membership request for user {} to join {}",
                admin.getEmail(), user.getEmail(), request.getOrganization().getName());

        return request;
    }

    /**
     * Reject a membership request (admin only)
     */
    @Transactional
    public MembershipRequest rejectMembership(UUID requestId, User admin) {
        // SECURITY: Verify admin has ADMIN role
        if (admin.getRole() != User.UserRole.ADMIN) {
            log.warn("Non-admin user {} attempted to reject membership request {}",
                    admin.getEmail(), requestId);
            throw new SecurityException("Only administrators can reject membership requests");
        }

        MembershipRequest request = membershipRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Membership request not found: " + requestId));

        // SECURITY: Verify admin belongs to the same organization as the request
        if (admin.getOrganization() == null ||
                !admin.getOrganization().getId().equals(request.getOrganization().getId())) {
            log.warn("Admin {} from different org attempted to reject request {} for org {}",
                    admin.getEmail(), requestId, request.getOrganization().getName());
            throw new SecurityException("Can only reject membership requests for your own organization");
        }

        if (request.getStatus() != MembershipRequest.Status.PENDING) {
            throw new IllegalStateException("Request is not pending, current status: " + request.getStatus());
        }

        // Update request
        request.setStatus(MembershipRequest.Status.REJECTED);
        request.setReviewedAt(LocalDateTime.now());
        request.setReviewedBy(admin);
        membershipRequestRepository.save(request);

        log.info("Admin {} rejected membership request for user {} to join {}",
                admin.getEmail(), request.getUser().getEmail(), request.getOrganization().getName());

        return request;
    }

    /**
     * Get pending requests for an organization
     */
    public List<MembershipRequest> getPendingRequests(Organization organization) {
        return membershipRequestRepository.findByOrganizationAndStatus(organization, MembershipRequest.Status.PENDING);
    }

    /**
     * Count pending requests for an organization
     */
    public long countPendingRequests(Organization organization) {
        return membershipRequestRepository.countByOrganizationAndStatus(organization, MembershipRequest.Status.PENDING);
    }

    /**
     * Get a user's pending membership request (if any)
     */
    public Optional<MembershipRequest> getUserPendingRequest(User user) {
        return membershipRequestRepository.findByUserAndStatus(user, MembershipRequest.Status.PENDING);
    }

    /**
     * Get membership request by ID
     */
    public Optional<MembershipRequest> getRequest(UUID requestId) {
        return membershipRequestRepository.findById(requestId);
    }
}
