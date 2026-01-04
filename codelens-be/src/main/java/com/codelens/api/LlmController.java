package com.codelens.api;

import com.codelens.llm.LlmProvider;
import com.codelens.llm.LlmProviderFactory;
import com.codelens.llm.LlmRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private final LlmProviderFactory providerFactory;
    private final LlmRouter llmRouter;

    public LlmController(LlmProviderFactory providerFactory, LlmRouter llmRouter) {
        this.providerFactory = providerFactory;
        this.llmRouter = llmRouter;
    }

    /**
     * Get all available LLM providers
     */
    @GetMapping("/providers")
    public ResponseEntity<List<ProviderInfo>> getProviders() {
        String defaultProviderName = providerFactory.getDefaultProvider().getName();
        List<ProviderInfo> providers = providerFactory.getProviderNames().stream()
            .map(name -> new ProviderInfo(
                name,
                providerFactory.isProviderAvailable(name),
                getProviderDescription(name),
                name.equals(defaultProviderName)
            ))
            .toList();
        return ResponseEntity.ok(providers);
    }

    /**
     * Get enabled providers only
     */
    @GetMapping("/providers/enabled")
    public ResponseEntity<List<String>> getEnabledProviders() {
        List<String> enabled = providerFactory.getEnabledProviders().stream()
            .map(LlmProvider::getName)
            .toList();
        return ResponseEntity.ok(enabled);
    }

    /**
     * Test a provider
     */
    @PostMapping("/providers/{name}/test")
    public ResponseEntity<TestResult> testProvider(@PathVariable String name) {
        try {
            if (!providerFactory.isProviderAvailable(name)) {
                return ResponseEntity.ok(new TestResult(false, "Provider not configured"));
            }

            LlmProvider provider = providerFactory.getProviderOrThrow(name);
            long start = System.currentTimeMillis();
            LlmProvider.LlmResponse response = provider.generate("Say 'OK' if you are working.");
            long elapsed = System.currentTimeMillis() - start;

            return ResponseEntity.ok(new TestResult(
                true,
                "Provider responding in " + elapsed + "ms"
            ));
        } catch (Exception e) {
            log.error("Provider test failed: {}", name, e);
            return ResponseEntity.ok(new TestResult(false, e.getMessage()));
        }
    }

    /**
     * Get routing configuration
     */
    @GetMapping("/routing")
    public ResponseEntity<Map<String, Object>> getRoutingConfig() {
        Map<String, Object> config = new HashMap<>();

        // Task to provider mapping
        Map<String, String> taskRouting = new HashMap<>();
        taskRouting.put("review", getProviderForTask("review"));
        taskRouting.put("summary", getProviderForTask("summary"));
        taskRouting.put("security", getProviderForTask("security"));
        taskRouting.put("describe", getProviderForTask("describe"));

        config.put("taskRouting", taskRouting);
        config.put("defaultProvider", providerFactory.getDefaultProvider().getName());

        return ResponseEntity.ok(config);
    }

    /**
     * Simple chat endpoint for testing
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        try {
            String providerName = request.provider() != null ?
                request.provider() : providerFactory.getDefaultProvider().getName();

            LlmProvider provider = providerFactory.getProviderOrThrow(providerName);
            LlmProvider.LlmResponse response = provider.generate(request.message());

            return ResponseEntity.ok(new ChatResponse(
                response.content(),
                providerName,
                response.inputTokens(),
                response.outputTokens()
            ));
        } catch (Exception e) {
            log.error("Chat failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private String getProviderForTask(String task) {
        try {
            return llmRouter.routeRequest(task).getName();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private String getProviderDescription(String name) {
        return switch (name) {
            case "glm" -> "ZhipuAI GLM-4 - Chinese LLM with strong code understanding";
            case "claude" -> "Anthropic Claude - Excellent for detailed code analysis";
            case "gemini" -> "Google Gemini - Fast and capable multi-modal model";
            case "ollama" -> "Ollama - Local LLM for privacy-focused reviews";
            case "openai" -> "OpenAI GPT - Industry-standard code review";
            default -> "Unknown provider";
        };
    }

    // DTOs

    public record ProviderInfo(
        String name,
        boolean available,
        String description,
        boolean isDefault
    ) {}

    public record TestResult(
        boolean success,
        String message
    ) {}

    public record ChatRequest(
        String message,
        String provider
    ) {}

    public record ChatResponse(
        String response,
        String provider,
        int inputTokens,
        int outputTokens
    ) {}
}
