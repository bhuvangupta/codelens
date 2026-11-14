package com.codelens.gate;

import com.codelens.gate.SecurityScanner.ScanContext;
import com.codelens.gate.SecurityScanner.ScanOutcome;
import com.codelens.git.GitProvider.CommitStatus;
import com.codelens.git.GitProvider.CommitStatus.State;
import com.codelens.git.GitProviderFactory;
import com.codelens.model.entity.Repository.GitProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SecurityGateServiceTest {

    private static final String SHA = "abc123";

    private com.codelens.git.GitProvider git;
    private GitProviderFactory factory;
    private final Executor executor = Executors.newCachedThreadPool();

    @BeforeEach
    void setUp() {
        git = mock(com.codelens.git.GitProvider.class);
        factory = mock(GitProviderFactory.class);
        when(factory.getProvider(GitProvider.GITHUB)).thenReturn(git);
    }

    @Test
    void disabledGatePostsNothing() {
        gate(false, "enforce", 5, scanner(GateCheck.SECRETS, c -> ScanOutcome.fail("1 secret"))).run();

        verifyNoInteractions(git);
    }

    @Test
    void enforceModeMapsCleanFindingsAndErrors() {
        gate(true, "enforce", 5,
            scanner(GateCheck.DEPS, c -> ScanOutcome.pass("No new vulnerabilities")),
            scanner(GateCheck.SAST, c -> ScanOutcome.fail("2 new findings")),
            scanner(GateCheck.SECRETS, c -> { throw new IllegalStateException("token=hunter2"); })
        ).run();

        Map<String, CommitStatus> finals = finalStatuses();
        assertEquals(State.SUCCESS, finals.get("codelens/deps").state());
        assertEquals(State.FAILURE, finals.get("codelens/sast").state());
        assertEquals("2 new findings", finals.get("codelens/sast").description());
        assertEquals(State.ERROR, finals.get("codelens/secrets").state());
        assertFalse(finals.get("codelens/secrets").description().contains("hunter2"),
            "exception text must not reach the PR");
    }

    @Test
    void enforceModeFailsClosedWhenScannerIsMissing() {
        gate(true, "enforce", 5, scanner(GateCheck.DEPS, c -> ScanOutcome.pass("ok"))).run();

        Map<String, CommitStatus> finals = finalStatuses();
        assertEquals(State.ERROR, finals.get("codelens/sast").state());
        assertEquals(State.ERROR, finals.get("codelens/secrets").state());
    }

    @Test
    void enforceModeFailsClosedOnTimeout() {
        gate(true, "enforce", 1, scanner(GateCheck.SECRETS, c -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ScanOutcome.pass("too late");
        })).run();

        CommitStatus status = finalStatuses().get("codelens/secrets");
        assertEquals(State.ERROR, status.state());
        assertTrue(status.description().contains("timed out"));
    }

    @Test
    void reportOnlyModeIsAlwaysGreenButDescribesTheResult() {
        gate(true, "report-only", 5,
            scanner(GateCheck.SAST, c -> ScanOutcome.fail("2 new findings")),
            scanner(GateCheck.SECRETS, c -> { throw new IllegalStateException("boom"); })
        ).run();

        Map<String, CommitStatus> finals = finalStatuses();
        finals.values().forEach(s -> assertEquals(State.SUCCESS, s.state()));
        assertEquals("Report-only: 2 new findings", finals.get("codelens/sast").description());
        assertEquals("Report-only: No scanner configured", finals.get("codelens/deps").description());
    }

    @Test
    void findingDetailsArePostedAsPrCommentEvenInReportOnlyMode() {
        gate(true, "report-only", 5,
            scanner(GateCheck.SECRETS, c -> ScanOutcome.fail("1 potential secret", "## details"))).run();

        verify(git).postComment("owner", "repo", 7, "## details");
        assertEquals(State.SUCCESS, finalStatuses().get("codelens/secrets").state());
    }

    @Test
    void pendingIsPostedBeforeTheResult() {
        gate(true, "enforce", 5, scanner(GateCheck.SECRETS, c -> ScanOutcome.pass("ok"))).run();

        List<CommitStatus> secrets = allStatuses().stream()
            .filter(s -> s.context().equals("codelens/secrets")).toList();
        assertEquals(List.of(State.PENDING, State.SUCCESS), secrets.stream().map(CommitStatus::state).toList());
    }

    @Test
    void oneStatusPostFailureDoesNotStopTheOthers() {
        doThrow(new RuntimeException("403")).when(git).setCommitStatus(any(), any(), any(),
            org.mockito.ArgumentMatchers.argThat(s -> s != null && s.context().equals("codelens/deps")));

        gate(true, "enforce", 5).run();

        assertEquals(State.ERROR, finalStatuses().get("codelens/secrets").state());
    }

    @Test
    void unknownModeIsRejectedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> gate(true, "enforced", 5));
    }

    @Test
    void duplicateScannersAreRejectedAtStartup() {
        assertThrows(IllegalStateException.class, () -> gate(true, "enforce", 5,
            scanner(GateCheck.DEPS, c -> ScanOutcome.pass("a")),
            scanner(GateCheck.DEPS, c -> ScanOutcome.pass("b"))));
    }

    // ---- helpers ----

    private interface Runner {
        void run();
    }

    private Runner gate(boolean enabled, String mode, int timeoutSeconds, SecurityScanner... scanners) {
        SecurityGateService service = new SecurityGateService(
            factory, List.of(scanners), executor, enabled, mode, timeoutSeconds);
        return () -> service.runForPullRequest(GitProvider.GITHUB, "owner", "repo", 7, SHA, "base456");
    }

    private interface ThrowingScan {
        ScanOutcome apply(ScanContext context) throws Exception;
    }

    private SecurityScanner scanner(GateCheck check, ThrowingScan body) {
        return new SecurityScanner() {
            @Override
            public GateCheck check() {
                return check;
            }

            @Override
            public ScanOutcome scan(ScanContext context) throws Exception {
                return body.apply(context);
            }
        };
    }

    private List<CommitStatus> allStatuses() {
        ArgumentCaptor<CommitStatus> captor = ArgumentCaptor.forClass(CommitStatus.class);
        verify(git, atLeastOnce()).setCommitStatus(eq("owner"), eq("repo"), eq(SHA), captor.capture());
        return captor.getAllValues();
    }

    /** Last status posted per context. */
    private Map<String, CommitStatus> finalStatuses() {
        Map<String, CommitStatus> last = new LinkedHashMap<>();
        allStatuses().forEach(s -> last.put(s.context(), s));
        return last;
    }

}
