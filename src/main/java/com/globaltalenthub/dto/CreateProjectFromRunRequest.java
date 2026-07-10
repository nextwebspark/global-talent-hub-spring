package com.globaltalenthub.dto;

/**
 * Auto-create payload used by the Talent Map flow: a search run becomes a project
 * ("search map") immediately, with the run's whole matched universe seeded as
 * {@code untriaged} companies — no client-side company selection.
 *
 * <p>Contrast with {@link CreateProjectRequest} (confirm-then-create), which carries an
 * explicit {@code companies} list. Here the companies are derived server-side from the
 * run's {@code parsed_criteria}.
 */
public record CreateProjectFromRunRequest(
    String name,
    ClientRef client,
    Long searchRunId
) {}
