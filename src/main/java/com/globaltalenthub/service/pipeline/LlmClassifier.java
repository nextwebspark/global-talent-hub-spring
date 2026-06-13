package com.globaltalenthub.service.pipeline;

/**
 * Narrow seam for the cheap vocabulary classifier (gemini-2.5-flash, temperature 0).
 * Implemented by {@code LlmService} (Phase 7). Kept as a tiny interface so the
 * pipeline filter is unit-testable without wiring the Vertex AI beans.
 */
public interface LlmClassifier {

    /** Single-shot completion of the classifier prompt; returns raw model text. */
    String classify(String prompt);
}
