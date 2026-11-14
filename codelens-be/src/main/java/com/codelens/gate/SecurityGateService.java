package com.codelens.gate;

import com.codelens.gate.SecurityScanner.ScanContext;
import com.codelens.gate.SecurityScanner.ScanOutcome;
import com.codelens.git.GitProvider.CommitStatus;
import com.codelens.git.GitProviderFactory;
import com.codelens.model.entity.Repository.GitProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Security merge gate. For every PR head commit it reports one commit status per
 * {@link GateCheck}: pending when the scan starts, then the result.
 *
 * It is deliberately independent of the LLM review pipeline, and in enforce mode it
 * fails closed: a finding, a scanner error, a timeout or a missing scanner all block.
 */
@Slf4j
@Service
public class SecurityGateService {

    public enum Mode { REPORT_ONLY, ENFORCE }

    private final GitProviderFactory gitProviderFactory;
    private final Executor scanExecutor;
    private final Map<GateCheck, SecurityScanner> scanners = new EnumMap<>(GateCheck.class);
    private final boolean enabled;
    private final Mode mode;
    private final int timeoutSeconds;

    @Autowired
    public SecurityGateService(
            GitProviderFactory gitProviderFactory,
            // ObjectProvider: a plain List fails startup while no scanner bean exists yet
            ObjectProvider<SecurityScanner> scannerBeans,
            @Qualifier("analysisTaskExecutor") Executor scanExecutor,
            @Value("${codelens.security-gate.enabled:false}") boolean enabled,
            @Value("${codelens.security-gate.mode:report-only}") String mode,
            @Value("${codelens.security-gate.timeout-seconds:300}") int timeoutSeconds) {
        this(gitProviderFactory, scannerBeans.orderedStream().toList(), scanExecutor, enabled, mode, timeoutSeconds);
    }

    SecurityGateService(GitProviderFactory gitProviderFactory, List<SecurityScanner> scannerBeans,
                        Executor scanExecutor, boolean enabled, String mode, int timeoutSeconds) {
        this.gitProviderFactory = gitProviderFactory;
        this.scanExecutor = scanExecutor;
        this.enabled = enabled;
        this.mode = parseMode(mode);
        this.timeoutSeconds = timeoutSeconds;
        for (SecurityScanner scanner : scannerBeans) {
            SecurityScanner previous = scanners.put(scanner.check(), scanner);
            if (previous != null) {
                throw new IllegalStateException("Two scanners registered for " + scanner.check()
                    + ": " + previous.getClass().getName() + " and " + scanner.getClass().getName());
            }
        }
        log.info("Security gate enabled={} mode={} scanners={}", enabled, this.mode, scanners.keySet());
    }

    private static Mode parseMode(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase().replace('_', '-');
        return switch (normalized) {
            case "report-only" -> Mode.REPORT_ONLY;
            case "enforce" -> Mode.ENFORCE;
            // A typo must not silently downgrade an enforcing gate
            default -> throw new IllegalArgumentException(
                "codelens.security-gate.mode must be 'report-only' or 'enforce', got: " + raw);
        };
    }

    @Async("webhookTaskExecutor")
    public void runForPullRequest(GitProvider provider, String owner, String repo, int prNumber,
                                  String headSha, String baseSha) {
        if (!enabled) {
            return;
        }
        com.codelens.git.GitProvider git = gitProviderFactory.getProvider(provider);
        ScanContext context = new ScanContext(provider, owner, repo, prNumber, headSha, baseSha);
        log.info("Security gate started for {}/{} #{} at {}", owner, repo, prNumber, headSha);

        Map<GateCheck, CompletableFuture<ScanOutcome>> running = new EnumMap<>(GateCheck.class);
        for (GateCheck check : GateCheck.values()) {
            SecurityScanner scanner = scanners.get(check);
            if (scanner == null) {
                continue;
            }
            post(git, context, check, CommitStatus.State.PENDING, "Scanning...");
            running.put(check, CompletableFuture.supplyAsync(() -> {
                try {
                    return scanner.scan(context);
                } catch (Exception e) {
                    throw new ScanFailedException(e);
                }
            }, scanExecutor));
        }

        for (GateCheck check : GateCheck.values()) {
            CompletableFuture<ScanOutcome> future = running.get(check);
            Result result = future == null
                ? new Result(Verdict.BROKEN, "No scanner configured", null)
                : await(check, context, future);
            report(git, context, check, result);
        }
    }

    private Result await(GateCheck check, ScanContext context, CompletableFuture<ScanOutcome> future) {
        try {
            ScanOutcome outcome = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (outcome == null) {
                return new Result(Verdict.BROKEN, "Scanner returned no result", null);
            }
            return new Result(outcome.passed() ? Verdict.CLEAN : Verdict.FINDINGS,
                outcome.summary(), outcome.details());
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("{} timed out after {}s for {}/{}@{}", check.context(), timeoutSeconds,
                context.owner(), context.repo(), context.headSha());
            return new Result(Verdict.BROKEN, "Scan timed out after " + timeoutSeconds + "s", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Verdict.BROKEN, "Scan interrupted", null);
        } catch (ExecutionException e) {
            log.error("{} failed for {}/{}@{}", check.context(), context.owner(), context.repo(),
                context.headSha(), e.getCause());
            // Exception text can carry paths or tokens; keep it in the log, not on the PR
            return new Result(Verdict.BROKEN, "Scanner error, see CodeLens logs", null);
        }
    }

    private void report(com.codelens.git.GitProvider git, ScanContext context, GateCheck check, Result result) {
        if (result.details() != null && !result.details().isBlank()) {
            // Posted in report-only mode too: there the comment is the report
            try {
                git.postComment(context.owner(), context.repo(), context.prNumber(), result.details());
            } catch (Exception e) {
                log.error("Could not post {} findings on {}/{} #{}", check.context(),
                    context.owner(), context.repo(), context.prNumber(), e);
            }
        }
        if (mode == Mode.REPORT_ONLY) {
            post(git, context, check, CommitStatus.State.SUCCESS, "Report-only: " + result.summary());
            return;
        }
        CommitStatus.State state = switch (result.verdict()) {
            case CLEAN -> CommitStatus.State.SUCCESS;
            case FINDINGS -> CommitStatus.State.FAILURE;
            case BROKEN -> CommitStatus.State.ERROR;
        };
        post(git, context, check, state, result.summary());
    }

    private void post(com.codelens.git.GitProvider git, ScanContext context, GateCheck check,
                      CommitStatus.State state, String description) {
        try {
            git.setCommitStatus(context.owner(), context.repo(), context.headSha(),
                new CommitStatus(check.context(), state, description, null));
        } catch (Exception e) {
            // If the result cannot be posted, a required check stays pending/missing and
            // GitHub keeps the merge blocked, so this is still fail-closed.
            log.error("Could not post {}={} on {}/{}@{}", check.context(), state,
                context.owner(), context.repo(), context.headSha(), e);
        }
    }

    private enum Verdict { CLEAN, FINDINGS, BROKEN }

    private record Result(Verdict verdict, String summary, String details) {}

    private static class ScanFailedException extends RuntimeException {
        ScanFailedException(Throwable cause) {
            super(cause);
        }
    }
}
