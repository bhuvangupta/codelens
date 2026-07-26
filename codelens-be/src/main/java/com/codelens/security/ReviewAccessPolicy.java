package com.codelens.security;

import com.codelens.model.entity.Review;
import com.codelens.model.entity.User;

import java.util.UUID;

/**
 * Multi-tenant access decision for reviews.
 *
 * <p>Fail-closed by design: a review whose organization cannot be resolved is
 * treated as inaccessible rather than public. Every review-creation path in
 * {@code ReviewService} assigns a repository with an organization, so an
 * unresolvable org means corrupt data.
 */
public final class ReviewAccessPolicy {

    private ReviewAccessPolicy() {}

    /**
     * @return the owning organization id, or null if it cannot be resolved.
     */
    public static UUID organizationIdOf(Review review) {
        if (review == null || review.getRepository() == null
                || review.getRepository().getOrganization() == null) {
            return null;
        }
        return review.getRepository().getOrganization().getId();
    }

    /**
     * @return the user's organization id, or null if it cannot be resolved.
     */
    public static UUID organizationIdOf(User user) {
        if (user == null || user.getOrganization() == null) {
            return null;
        }
        return user.getOrganization().getId();
    }

    /**
     * @return the organization of the user who submitted the review, or null.
     *         Webhook-created reviews have no user, so this is frequently null.
     */
    public static UUID submitterOrganizationIdOf(Review review) {
        if (review == null || review.getUser() == null
                || review.getUser().getOrganization() == null) {
            return null;
        }
        return review.getUser().getOrganization().getId();
    }

    /**
     * Access requires the caller's organization to be known, and to match
     * either the review's repository organization or the organization of the
     * user who submitted it.
     *
     * <p>Both are accepted because the two can legitimately differ: repositories
     * created by webhook derive their organization from the Git namespace, while
     * users derive theirs from email-domain mapping. Requiring only the
     * repository org would 404 reviews that the caller's own organization
     * submitted and can see in its own listings.
     */
    public static boolean canAccess(UUID userOrgId, UUID repoOrgId, UUID submitterOrgId) {
        if (userOrgId == null) {
            return false;
        }
        return userOrgId.equals(repoOrgId) || userOrgId.equals(submitterOrgId);
    }
}
