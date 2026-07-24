package com.codelens.security;

import com.codelens.model.entity.Organization;
import com.codelens.model.entity.Repository;
import com.codelens.model.entity.Review;
import com.codelens.model.entity.User;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReviewAccessPolicyTest {

    private static final UUID ORG_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static Review reviewInOrg(UUID orgId) {
        Organization org = new Organization();
        org.setId(orgId);
        Repository repo = new Repository();
        repo.setOrganization(org);
        Review review = new Review();
        review.setRepository(repo);
        return review;
    }

    @Nested
    class CanAccess {
        @Test
        void allowsMatchingOrganisations() {
            assertTrue(ReviewAccessPolicy.canAccess(ORG_A, ORG_A));
        }

        @Test
        void deniesDifferentOrganisations() {
            assertFalse(ReviewAccessPolicy.canAccess(ORG_A, ORG_B));
        }

        @Test
        void deniesWhenEitherSideIsUnknown() {
            // Fail-closed: a review with no resolvable org is corrupt data, not public data
            assertFalse(ReviewAccessPolicy.canAccess(null, ORG_A));
            assertFalse(ReviewAccessPolicy.canAccess(ORG_A, null));
            assertFalse(ReviewAccessPolicy.canAccess(null, null));
        }
    }

    @Nested
    class OrganizationIdOfReview {
        @Test
        void readsOrgThroughRepository() {
            assertEquals(ORG_A, ReviewAccessPolicy.organizationIdOf(reviewInOrg(ORG_A)));
        }

        @Test
        void returnsNullWhenReviewIsNull() {
            assertNull(ReviewAccessPolicy.organizationIdOf((Review) null));
        }

        @Test
        void returnsNullWhenRepositoryIsMissing() {
            assertNull(ReviewAccessPolicy.organizationIdOf(new Review()));
        }

        @Test
        void returnsNullWhenOrganizationIsMissing() {
            Review review = new Review();
            review.setRepository(new Repository());
            assertNull(ReviewAccessPolicy.organizationIdOf(review));
        }
    }

    @Nested
    class OrganizationIdOfUser {
        @Test
        void readsOrgDirectly() {
            Organization org = new Organization();
            org.setId(ORG_B);
            User user = new User();
            user.setOrganization(org);
            assertEquals(ORG_B, ReviewAccessPolicy.organizationIdOf(user));
        }

        @Test
        void returnsNullWhenUserOrOrgIsMissing() {
            assertNull(ReviewAccessPolicy.organizationIdOf((User) null));
            assertNull(ReviewAccessPolicy.organizationIdOf(new User()));
        }
    }
}
