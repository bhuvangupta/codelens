package com.codelens.gate;

import com.codelens.git.GitProvider.CommitStatus;
import com.codelens.git.GitProvider.PullRequestInfo;
import com.codelens.git.GitProviderFactory;
import com.codelens.model.entity.Repository.GitProvider;
import com.codelens.model.entity.SecurityGateBypass;
import com.codelens.model.entity.SecurityGateBypass.Via;
import com.codelens.model.entity.User;
import com.codelens.repository.SecurityGateBypassRepository;
import com.codelens.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Emergency override of the security gate for one PR head commit.
 *
 * Who may bypass is a list of CodeLens account emails in server configuration, not
 * something editable through the API: anyone who could edit it could grant themselves
 * the bypass. Every bypass needs a reason and is written to an insert-only audit table.
 */
@Slf4j
@Service
public class SecurityGateBypassService implements SecurityGateService.BypassLookup {

    /** "/codelens bypass <reason>" as the first line of a PR comment. */
    private static final Pattern COMMAND =
        Pattern.compile("^\\s*/codelens\\s+bypass\\b[ \\t]*([^\\r\\n]*)", Pattern.CASE_INSENSITIVE);
    private static final int MIN_REASON_LENGTH = 10;
    private static final int MAX_REASON_LENGTH = 500;

    public enum Outcome {
        APPLIED, NOT_A_COMMAND, GATE_DISABLED, NOT_AUTHORIZED, REASON_REQUIRED, PR_NOT_OPEN
    }

    public record Result(Outcome outcome, String headSha) {
        static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }
    }

    private final GitProviderFactory gitProviderFactory;
    private final UserRepository userRepository;
    private final SecurityGateBypassRepository bypassRepository;
    private final boolean gateEnabled;
    private final Set<String> allowedEmails;

    public SecurityGateBypassService(
            GitProviderFactory gitProviderFactory,
            UserRepository userRepository,
            SecurityGateBypassRepository bypassRepository,
            @Value("${codelens.security-gate.enabled:false}") boolean gateEnabled,
            @Value("${codelens.security-gate.bypass.users:}") String allowedEmails) {
        this.gitProviderFactory = gitProviderFactory;
        this.userRepository = userRepository;
        this.bypassRepository = bypassRepository;
        this.gateEnabled = gateEnabled;
        this.allowedEmails = Arrays.stream(allowedEmails.split(","))
            .map(email -> email.trim().toLowerCase()).filter(email -> !email.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
        log.info("Security gate bypass allowed for {} user(s)", this.allowedEmails.size());
    }

    /** Entry point for the issue_comment webhook. Runs off the webhook thread. */
    @Async("webhookTaskExecutor")
    public void handlePullRequestComment(GitProvider provider, String owner, String repo, int prNumber,
                                         String githubLogin, String commentBody) {
        try {
            bypassFromComment(provider, owner, repo, prNumber, githubLogin, commentBody);
        } catch (Exception e) {
            log.error("Bypass command on {}/{} #{} failed", owner, repo, prNumber, e);
        }
    }

    /**
     * @param githubLogin the commenter, taken from the signature-verified webhook payload
     */
    public Result bypassFromComment(GitProvider provider, String owner, String repo, int prNumber,
                                    String githubLogin, String commentBody) {
        Matcher command = COMMAND.matcher(commentBody == null ? "" : commentBody);
        if (!command.find()) {
            return Result.of(Outcome.NOT_A_COMMAND);
        }
        if (!gateEnabled) {
            return Result.of(Outcome.GATE_DISABLED);
        }
        com.codelens.git.GitProvider git = gitProviderFactory.getProvider(provider);

        Optional<String> actorEmail = allowlistedEmailForGithubLogin(githubLogin);
        if (actorEmail.isEmpty()) {
            log.warn("Refused bypass on {}/{} #{}: GitHub user {} is not allowed", owner, repo, prNumber, githubLogin);
            reply(git, owner, repo, prNumber, ":no_entry: Security gate bypass refused: "
                + GateMarkdown.cell(githubLogin) + " is not allowed to bypass. The checks are unchanged.");
            return Result.of(Outcome.NOT_AUTHORIZED);
        }
        String reason = command.group(1).trim();
        if (reason.length() < MIN_REASON_LENGTH) {
            reply(git, owner, repo, prNumber, ":no_entry: Security gate bypass refused: a reason is required. "
                + "Use `/codelens bypass <why this cannot wait>`. The checks are unchanged.");
            return Result.of(Outcome.REASON_REQUIRED);
        }
        return apply(provider, git, owner, repo, prNumber, actorEmail.get(), githubLogin, reason, Via.PR_COMMENT);
    }

    /**
     * @param actorEmail the email of the signed-in CodeLens user making the request
     */
    public Result bypassFromCodeLens(GitProvider provider, String owner, String repo, int prNumber,
                                     String actorEmail, String reason) {
        if (!gateEnabled) {
            return Result.of(Outcome.GATE_DISABLED);
        }
        if (actorEmail == null || !allowedEmails.contains(actorEmail.trim().toLowerCase())) {
            log.warn("Refused bypass on {}/{} #{}: {} is not allowed", owner, repo, prNumber, actorEmail);
            return Result.of(Outcome.NOT_AUTHORIZED);
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() < MIN_REASON_LENGTH) {
            return Result.of(Outcome.REASON_REQUIRED);
        }
        return apply(provider, gitProviderFactory.getProvider(provider), owner, repo, prNumber,
            actorEmail.trim().toLowerCase(), null, trimmed, Via.CODELENS);
    }

    /**
     * The allowlist is walked by email and each account's GitHub username compared, rather
     * than looking the commenter up by GitHub username. That way another CodeLens account
     * claiming the same GitHub username can neither gain the bypass nor break it.
     */
    private Optional<String> allowlistedEmailForGithubLogin(String githubLogin) {
        if (githubLogin == null || githubLogin.isBlank()) {
            return Optional.empty();
        }
        for (String email : allowedEmails) {
            Optional<User> user = userRepository.findByEmail(email);
            if (user.isPresent() && githubLogin.equalsIgnoreCase(user.get().getGithubUsername())) {
                return Optional.of(email);
            }
        }
        return Optional.empty();
    }

    private Result apply(GitProvider provider, com.codelens.git.GitProvider git, String owner, String repo,
                         int prNumber, String actorEmail, String githubLogin, String rawReason, Via via) {
        PullRequestInfo pr = git.getPullRequest(owner, repo, prNumber);
        if (!"open".equalsIgnoreCase(pr.state())) {
            if (via == Via.PR_COMMENT) {
                reply(git, owner, repo, prNumber, ":no_entry: Security gate bypass refused: this PR is not open.");
            }
            return Result.of(Outcome.PR_NOT_OPEN);
        }
        String headSha = pr.headCommitSha();
        String reason = rawReason.length() > MAX_REASON_LENGTH ? rawReason.substring(0, MAX_REASON_LENGTH) : rawReason;

        // The audit row is written first: if it cannot be saved, nothing is bypassed
        bypassRepository.save(SecurityGateBypass.builder()
            .provider(provider)
            .repoFullName(owner + "/" + repo)
            .prNumber(prNumber)
            .headSha(headSha)
            .actorEmail(actorEmail)
            .actorGithubLogin(githubLogin)
            .via(via)
            .reason(reason)
            .findingsSnapshot(snapshot(git, owner, repo, headSha))
            .build());
        log.warn("SECURITY GATE BYPASSED on {}/{} #{} at {} by {} via {}: {}",
            owner, repo, prNumber, headSha, actorEmail, via, reason);

        String description = statusDescription(actorEmail, reason);
        for (GateCheck check : GateCheck.values()) {
            git.setCommitStatus(owner, repo, headSha,
                new CommitStatus(check.context(), CommitStatus.State.SUCCESS, description, null));
        }
        reply(git, owner, repo, prNumber, ":warning: **Security gate bypassed** by " + GateMarkdown.cell(actorEmail)
            + " for commit `" + headSha.substring(0, Math.min(8, headSha.length())) + "`.\n\n"
            + "> " + GateMarkdown.cell(reason) + "\n\n"
            + "The findings were not fixed. This applies to this commit only: a new push is checked again.");
        return new Result(Outcome.APPLIED, headSha);
    }

    /** What the gate's checks said at the moment of the bypass, for the audit record. */
    private String snapshot(com.codelens.git.GitProvider git, String owner, String repo, String headSha) {
        try {
            Set<String> ours = Arrays.stream(GateCheck.values()).map(GateCheck::context).collect(Collectors.toSet());
            List<CommitStatus> statuses = git.getCommitStatuses(owner, repo, headSha);
            String text = statuses.stream()
                .filter(status -> ours.contains(status.context()))
                .map(status -> status.context() + ": " + status.state() + " - " + status.description())
                .collect(Collectors.joining("\n"));
            return text.isEmpty() ? "no gate results on this commit" : text;
        } catch (Exception e) {
            // An emergency bypass must not depend on being able to read the statuses back
            log.warn("Could not snapshot gate statuses for {}/{}@{}", owner, repo, headSha, e);
            return "unavailable";
        }
    }

    private static String statusDescription(String actorEmail, String reason) {
        return "Bypassed by " + actorEmail + ": " + reason.replaceAll("\\s+", " ");
    }

    private void reply(com.codelens.git.GitProvider git, String owner, String repo, int prNumber, String body) {
        try {
            git.postComment(owner, repo, prNumber, body);
        } catch (Exception e) {
            log.error("Could not post bypass reply on {}/{} #{}", owner, repo, prNumber, e);
        }
    }

    /**
     * Used by the gate when a scan finishes: a result that arrives after the bypass
     * (a slow scan, a webhook redelivery) must not turn the commit red again.
     */
    @Override
    public Optional<String> activeBypass(GitProvider provider, String owner, String repo, String headSha) {
        return bypassRepository
            .findFirstByProviderAndRepoFullNameAndHeadShaOrderByCreatedAtDesc(provider, owner + "/" + repo, headSha)
            .map(bypass -> statusDescription(bypass.getActorEmail(), bypass.getReason()));
    }
}
