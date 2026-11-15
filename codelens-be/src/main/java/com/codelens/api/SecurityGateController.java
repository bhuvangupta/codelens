package com.codelens.api;

import com.codelens.gate.SecurityGateBypassService;
import com.codelens.gate.SecurityGateBypassService.Result;
import com.codelens.git.GitProviderFactory;
import com.codelens.git.GitProviderFactory.ParsedPrUrl;
import com.codelens.security.AuthenticatedUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Security gate actions for signed-in CodeLens users.
 */
@Slf4j
@RestController
@RequestMapping("/api/security-gate")
public class SecurityGateController {

    private final SecurityGateBypassService bypassService;
    private final GitProviderFactory gitProviderFactory;

    public SecurityGateController(SecurityGateBypassService bypassService, GitProviderFactory gitProviderFactory) {
        this.bypassService = bypassService;
        this.gitProviderFactory = gitProviderFactory;
    }

    public record BypassRequest(String prUrl, String reason) {}

    /**
     * Override the security gate for the current head commit of a PR. Only for the
     * users listed in codelens.security-gate.bypass.users; the caller's identity is the
     * email of the signed-in account, never anything from the request body.
     */
    @PostMapping("/bypass")
    public ResponseEntity<Map<String, String>> bypass(@RequestBody BypassRequest request,
                                                      @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return respond(HttpStatus.UNAUTHORIZED, "Sign in to CodeLens first", null);
        }
        ParsedPrUrl pr;
        try {
            pr = gitProviderFactory.parsePrUrl(request.prUrl());
        } catch (IllegalArgumentException e) {
            return respond(HttpStatus.BAD_REQUEST, "prUrl is not a pull request URL", null);
        }

        Result result = bypassService.bypassFromCodeLens(
            pr.provider(), pr.owner(), pr.repo(), pr.prNumber(), auth.email(), request.reason());

        return switch (result.outcome()) {
            case APPLIED -> respond(HttpStatus.OK, "Security gate bypassed for this commit", result.headSha());
            case NOT_AUTHORIZED -> respond(HttpStatus.FORBIDDEN, "You are not allowed to bypass the security gate", null);
            case REASON_REQUIRED -> respond(HttpStatus.BAD_REQUEST, "A reason of at least 10 characters is required", null);
            case PR_NOT_OPEN -> respond(HttpStatus.CONFLICT, "The pull request is not open", null);
            case GATE_DISABLED -> respond(HttpStatus.CONFLICT, "The security gate is not enabled", null);
            case NOT_A_COMMAND -> respond(HttpStatus.BAD_REQUEST, "Invalid request", null);
        };
    }

    private static ResponseEntity<Map<String, String>> respond(HttpStatus status, String message, String headSha) {
        return ResponseEntity.status(status).body(headSha == null
            ? Map.of("message", message)
            : Map.of("message", message, "headSha", headSha));
    }
}
