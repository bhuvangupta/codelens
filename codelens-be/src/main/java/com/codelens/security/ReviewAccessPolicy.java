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
     * Access requires both organizations to be known and identical.
     */
    public static boolean canAccess(UUID userOrgId, UUID reviewOrgId) {
        return userOrgId != null && userOrgId.equals(reviewOrgId);
    }
}
