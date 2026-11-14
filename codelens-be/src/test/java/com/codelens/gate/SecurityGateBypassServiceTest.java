package com.codelens.gate;

import com.codelens.gate.SecurityGateBypassService.Outcome;
import com.codelens.gate.SecurityGateBypassService.Result;
import com.codelens.git.GitProvider.CommitStatus;
import com.codelens.git.GitProvider.CommitStatus.State;
import com.codelens.git.GitProvider.PullRequestInfo;
import com.codelens.git.GitProviderFactory;
import com.codelens.model.entity.Repository.GitProvider;
import com.codelens.model.entity.SecurityGateBypass;
import com.codelens.model.entity.SecurityGateBypass.Via;
import com.codelens.model.entity.User;
import com.codelens.repository.SecurityGateBypassRepository;
import com.codelens.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SecurityGateBypassServiceTest {

    private static final String ADMIN = "bhuvan@ofbusiness.in";
    private static final String HEAD = "abcdef1234567890";

    private com.codelens.git.GitProvider git;
    private UserRepository users;
    private SecurityGateBypassRepository bypasses;
    private GitProviderFactory factory;

    @BeforeEach
    void setUp() {
        git = mock(com.codelens.git.GitProvider.class);
        factory = mock(GitProviderFactory.class);
        when(factory.getProvider(GitProvider.GITHUB)).thenReturn(git);
        users = mock(UserRepository.class);
        bypasses = mock(SecurityGateBypassRepository.class);

        when(users.findByEmail(ADMIN)).thenReturn(Optional.of(user(ADMIN, "BhuvanGH")));
        when(git.getPullRequest("owner", "repo", 7)).thenReturn(pr("OPEN"));
        when(git.getCommitStatuses("owner", "repo", HEAD)).thenReturn(List.of(
            new CommitStatus("codelens/secrets", State.FAILURE, "1 potential secret in 2 commits", null),
            new CommitStatus("ci/build", State.SUCCESS, "ok", null)));
    }

    @Test
    void allowlistedUserBypassesFromPrComment() {
        Result result = comment("bhuvangh", "/codelens bypass prod is down, hotfix for INC-42\nmore text");

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(HEAD, result.headSha());

        SecurityGateBypass saved = savedRecord();
        assertEquals(ADMIN, saved.getActorEmail());
        assertEquals("bhuvangh", saved.getActorGithubLogin());
        assertEquals(Via.PR_COMMENT, saved.getVia());
        assertEquals("prod is down, hotfix for INC-42", saved.getReason());
        assertEquals("owner/repo", saved.getRepoFullName());
        assertEquals(HEAD, saved.getHeadSha());
        assertEquals("codelens/secrets: FAILURE - 1 potential secret in 2 commits", saved.getFindingsSnapshot());

        ArgumentCaptor<CommitStatus> statuses = ArgumentCaptor.forClass(CommitStatus.class);
        verify(git, times(3)).setCommitStatus(eq("owner"), eq("repo"), eq(HEAD), statuses.capture());
        assertEquals(List.of("codelens/deps", "codelens/sast", "codelens/secrets"),
            statuses.getAllValues().stream().map(CommitStatus::context).toList());
        statuses.getAllValues().forEach(status -> {
            assertEquals(State.SUCCESS, status.state());
            assertEquals("Bypassed by " + ADMIN + ": prod is down, hotfix for INC-42", status.description());
        });
        verify(git).postComment(eq("owner"), eq("repo"), eq(7), org.mockito.ArgumentMatchers.contains("Security gate bypassed"));
    }

    @Test
    void auditRecordIsWrittenBeforeAnyStatusChanges() {
        comment("bhuvangh", "/codelens bypass prod is down, hotfix");

        InOrder order = inOrder(bypasses, git);
        order.verify(bypasses).save(any());
        order.verify(git, times(3)).setCommitStatus(any(), any(), any(), any());
    }

    @Test
    void nothingIsBypassedWhenTheAuditRecordCannotBeSaved() {
        when(bypasses.save(any())).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> comment("bhuvangh", "/codelens bypass prod is down, hotfix"));
        verify(git, never()).setCommitStatus(any(), any(), any(), any());
    }

    @Test
    void otherGithubUsersAreRefusedAndToldSo() {
        when(users.findByGithubUsername("mallory")).thenReturn(Optional.of(user("mallory@evil.example", "mallory")));

        Result result = comment("mallory", "/codelens bypass trust me, it is fine");

        assertEquals(Outcome.NOT_AUTHORIZED, result.outcome());
        verifyNoInteractions(bypasses);
        verify(git, never()).setCommitStatus(any(), any(), any(), any());
        verify(git).postComment(eq("owner"), eq("repo"), eq(7), org.mockito.ArgumentMatchers.contains("refused"));
    }

    @Test
    void allowlistedAccountWithoutGithubUsernameCannotBypassFromComment() {
        when(users.findByEmail(ADMIN)).thenReturn(Optional.of(user(ADMIN, null)));

        assertEquals(Outcome.NOT_AUTHORIZED, comment("bhuvangh", "/codelens bypass prod is down, hotfix").outcome());
        assertEquals(Outcome.NOT_AUTHORIZED, comment("", "/codelens bypass prod is down, hotfix").outcome());
    }

    @Test
    void reasonIsMandatory() {
        assertEquals(Outcome.REASON_REQUIRED, comment("bhuvangh", "/codelens bypass").outcome());
        assertEquals(Outcome.REASON_REQUIRED, comment("bhuvangh", "/codelens bypass   asap  ").outcome());
        // The reason must be on the command line itself, not somewhere below it
        assertEquals(Outcome.REASON_REQUIRED, comment("bhuvangh", "/codelens bypass\nprod is down, hotfix").outcome());
        verifyNoInteractions(bypasses);
    }

    @Test
    void ordinaryCommentsAreIgnoredSilently() {
        assertEquals(Outcome.NOT_A_COMMAND, comment("mallory", "LGTM").outcome());
        assertEquals(Outcome.NOT_A_COMMAND, comment("bhuvangh", "please do not /codelens bypass this").outcome());
        assertEquals(Outcome.NOT_A_COMMAND, comment("bhuvangh", "> /codelens bypass quoted text here").outcome());
        assertEquals(Outcome.NOT_A_COMMAND, comment("bhuvangh", "/codelens bypassed already I think").outcome());
        assertEquals(Outcome.NOT_A_COMMAND, comment("bhuvangh", null).outcome());
        verifyNoInteractions(git, bypasses);
    }

    @Test
    void closedPullRequestCannotBeBypassed() {
        when(git.getPullRequest("owner", "repo", 7)).thenReturn(pr("CLOSED"));

        assertEquals(Outcome.PR_NOT_OPEN, comment("bhuvangh", "/codelens bypass prod is down, hotfix").outcome());
        verifyNoInteractions(bypasses);
    }

    @Test
    void disabledGateIgnoresTheCommand() {
        SecurityGateBypassService disabled = new SecurityGateBypassService(factory, users, bypasses, false, ADMIN);

        Result result = disabled.bypassFromComment(GitProvider.GITHUB, "owner", "repo", 7, "bhuvangh",
            "/codelens bypass prod is down, hotfix");

        assertEquals(Outcome.GATE_DISABLED, result.outcome());
        verifyNoInteractions(git, bypasses);
    }

    @Test
    void emptyAllowlistMeansNobody() {
        SecurityGateBypassService nobody = new SecurityGateBypassService(factory, users, bypasses, true, " , ");

        assertEquals(Outcome.NOT_AUTHORIZED, nobody.bypassFromComment(GitProvider.GITHUB, "owner", "repo", 7,
            "bhuvangh", "/codelens bypass prod is down, hotfix").outcome());
        assertEquals(Outcome.NOT_AUTHORIZED, nobody.bypassFromCodeLens(GitProvider.GITHUB, "owner", "repo", 7,
            ADMIN, "prod is down, hotfix").outcome());
    }

    @Test
    void allowlistedUserBypassesFromCodeLens() {
        Result result = service().bypassFromCodeLens(GitProvider.GITHUB, "owner", "repo", 7,
            "Bhuvan@OfBusiness.in", "prod is down, hotfix for INC-42");

        assertEquals(Outcome.APPLIED, result.outcome());
        SecurityGateBypass saved = savedRecord();
        assertEquals(ADMIN, saved.getActorEmail());
        assertEquals(Via.CODELENS, saved.getVia());
        verify(git, times(3)).setCommitStatus(eq("owner"), eq("repo"), eq(HEAD), any());
    }

    @Test
    void otherCodeLensUsersAreRefused() {
        SecurityGateBypassService service = service();

        assertEquals(Outcome.NOT_AUTHORIZED, service.bypassFromCodeLens(GitProvider.GITHUB, "owner", "repo", 7,
            "someone@ofbusiness.in", "prod is down, hotfix").outcome());
        assertEquals(Outcome.NOT_AUTHORIZED, service.bypassFromCodeLens(GitProvider.GITHUB, "owner", "repo", 7,
            null, "prod is down, hotfix").outcome());
        assertEquals(Outcome.REASON_REQUIRED, service.bypassFromCodeLens(GitProvider.GITHUB, "owner", "repo", 7,
            ADMIN, "  ").outcome());
        verifyNoInteractions(bypasses);
        verify(git, never()).setCommitStatus(any(), any(), any(), any());
    }

    @Test
    void bypassStillWorksWhenStatusesCannotBeReadBack() {
        when(git.getCommitStatuses(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("502"));

        assertEquals(Outcome.APPLIED, comment("bhuvangh", "/codelens bypass prod is down, hotfix").outcome());
        assertEquals("unavailable", savedRecord().getFindingsSnapshot());
    }

    @Test
    void reasonCannotInjectMarkdownOrMentionsIntoThePrComment() {
        comment("bhuvangh", "/codelens bypass urgent @everyone [click](http://evil) <b>now</b>");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(git).postComment(eq("owner"), eq("repo"), anyInt(), body.capture());
        assertFalse(body.getValue().contains("@everyone"));
        assertFalse(body.getValue().contains("[click]"));
        assertFalse(body.getValue().contains("<b>"));
    }

    @Test
    void gateSeesTheBypassForThatCommitOnly() {
        SecurityGateBypass record = SecurityGateBypass.builder().actorEmail(ADMIN).reason("prod is  down").build();
        when(bypasses.findFirstByProviderAndRepoFullNameAndHeadShaOrderByCreatedAtDesc(
            GitProvider.GITHUB, "owner/repo", HEAD)).thenReturn(Optional.of(record));
        SecurityGateBypassService service = service();

        assertEquals(Optional.of("Bypassed by " + ADMIN + ": prod is down"),
            service.activeBypass(GitProvider.GITHUB, "owner", "repo", HEAD));
        assertTrue(service.activeBypass(GitProvider.GITHUB, "owner", "repo", "newpush999").isEmpty());
    }

    private SecurityGateBypassService service() {
        return new SecurityGateBypassService(factory, users, bypasses, true, " Bhuvan@OfBusiness.in ,");
    }

    private Result comment(String login, String body) {
        return service().bypassFromComment(GitProvider.GITHUB, "owner", "repo", 7, login, body);
    }

    private SecurityGateBypass savedRecord() {
        ArgumentCaptor<SecurityGateBypass> captor = ArgumentCaptor.forClass(SecurityGateBypass.class);
        verify(bypasses).save(captor.capture());
        return captor.getValue();
    }

    private static User user(String email, String githubUsername) {
        return User.builder().email(email).name("n").provider("google").providerId("1")
            .githubUsername(githubUsername).build();
    }

    private static PullRequestInfo pr(String state) {
        return new PullRequestInfo(7, "t", "d", "u", "author", "main", "feature", HEAD, "base", state, 1, 0, 1);
    }
}
