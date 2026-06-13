package com.globaltalenthub.service.pipeline;

/**
 * Tunables for feeding an uploaded brief/PD into the universe classifier.
 * Port of briefConfig.ts — env-overridable so caps are not magic numbers.
 */
public final class BriefConfig {

    private BriefConfig() {}

    /** Max chars of extracted PD text sent to the classifier (non-confidential path). */
    public static final int CLASSIFIER_CHAR_LIMIT = intFromEnv("BRIEF_CLASSIFIER_CHAR_LIMIT", 4000);

    /** Max chars of PD text fed to the confidential-summary call. */
    public static final int SUMMARY_INPUT_CHAR_LIMIT = intFromEnv("BRIEF_SUMMARY_INPUT_CHAR_LIMIT", 8000);

    /** Max output tokens for the confidential-summary call. */
    public static final int SUMMARY_MAX_TOKENS = intFromEnv("BRIEF_SUMMARY_MAX_TOKENS", 300);

    private static int intFromEnv(String name, int fallback) {
        String raw = System.getenv(name);
        if (raw == null) return fallback;
        try {
            int n = Integer.parseInt(raw.trim());
            return n > 0 ? n : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
