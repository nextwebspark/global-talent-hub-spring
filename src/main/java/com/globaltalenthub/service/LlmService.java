package com.globaltalenthub.service;

import com.globaltalenthub.service.pipeline.LlmClassifier;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Central LLM access — port of callLlmWithFallback / the flash classifier shim.
 * All calls go through Vertex AI Gemini (pro primary, flash fallback). Implements
 * {@link LlmClassifier} so the pipeline filter can depend on a narrow seam.
 *
 * <p>Every call is bounded by {@code app.llm.call-timeout-ms} on a dedicated executor,
 * so a hung upstream request cannot pin the SSE worker for the full SSE timeout. A
 * timed-out pro call falls through to flash; a timed-out flash call surfaces as an error
 * the callers already handle (classifier/brief fail open).
 *
 * <p>Excluded from the {@code test} profile (depends on the Vertex-backed beans);
 * unit tests mock {@link LlmClassifier} directly.
 */
@Service
@Profile("!test")
@Slf4j
public class LlmService implements LlmClassifier {

    private final ChatClient geminiPro;
    private final ChatClient geminiFlash;
    private final Duration callTimeout;
    private final ExecutorService llmExecutor;

    public LlmService(ChatClient geminiPro,
                      @Qualifier("geminiFlash") ChatClient geminiFlash,
                      @Value("${app.llm.call-timeout-ms:30000}") long callTimeoutMs) {
        this.geminiPro = geminiPro;
        this.geminiFlash = geminiFlash;
        this.callTimeout = Duration.ofMillis(callTimeoutMs);
        this.llmExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "llm-call");
            t.setDaemon(true);
            return t;
        });
    }

    /** Pro primary, flash fallback on any error (incl. timeout). Returns response text. */
    public String callWithFallback(String systemPrompt, String userPrompt) {
        try {
            return withTimeout(() -> geminiPro.prompt().system(systemPrompt).user(userPrompt).call().content());
        } catch (Exception e) {
            log.warn("[LLM] gemini-pro failed ({}), falling back to flash", e.getMessage());
            return withTimeout(() -> geminiFlash.prompt().system(systemPrompt).user(userPrompt).call().content());
        }
    }

    /** Flash-only, single-shot completion — used by the vocabulary classifier. */
    @Override
    public String classify(String prompt) {
        return withTimeout(() -> geminiFlash.prompt().user(prompt).call().content());
    }

    // Run a blocking LLM call on the dedicated pool, bounded by callTimeout. On timeout
    // the future is cancelled and a RuntimeException is thrown (callers treat it as failure).
    private String withTimeout(Supplier<String> call) {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(call, llmExecutor);
        try {
            return future.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException("LLM call timed out after " + callTimeout.toMillis() + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException(cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM call interrupted");
        }
    }

    @PreDestroy
    void shutdown() {
        llmExecutor.shutdownNow();
    }
}
