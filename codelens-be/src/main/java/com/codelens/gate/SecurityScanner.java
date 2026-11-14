package com.codelens.gate;

import com.codelens.model.entity.Repository.GitProvider;

/**
 * A scanner backing one {@link GateCheck}. Register an implementation as a Spring bean
 * and the gate picks it up; at most one scanner per check.
 *
 * Implementations must not swallow their own failures: throw, and the gate decides
 * (fail closed in enforce mode). Returning "passed" means the scan actually ran clean.
 */
public interface SecurityScanner {

    GateCheck check();

    ScanOutcome scan(ScanContext context) throws Exception;

    record ScanContext(GitProvider provider, String owner, String repo, int prNumber,
                       String headSha, String baseSha) {}

    /**
     * @param passed  true when nothing gate-worthy was found
     * @param summary one line for the commit status, e.g. "2 new HIGH vulnerabilities"
     */
    record ScanOutcome(boolean passed, String summary) {
        public static ScanOutcome pass(String summary) {
            return new ScanOutcome(true, summary);
        }

        public static ScanOutcome fail(String summary) {
            return new ScanOutcome(false, summary);
        }
    }
}
