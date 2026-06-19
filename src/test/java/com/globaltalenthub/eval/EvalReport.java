package com.globaltalenthub.eval;

import java.util.ArrayList;
import java.util.List;

/** Collects per-case pass/fail results for one eval layer and renders a scorecard. */
final class EvalReport {

    private record Result(String name, String detail, List<String> failures) {
        boolean passed() {
            return failures.isEmpty();
        }
    }

    private final String layer;
    private final List<Result> results = new ArrayList<>();

    EvalReport(String layer) {
        this.layer = layer;
    }

    void record(String name, String detail, List<String> failures) {
        results.add(new Result(name, detail, List.copyOf(failures)));
    }

    /** Fraction of cases with no failures, 0..1 (1.0 when there are no cases). */
    double passRate() {
        if (results.isEmpty()) return 1.0;
        long passed = results.stream().filter(Result::passed).count();
        return (double) passed / results.size();
    }

    @Override
    public String toString() {
        long passed = results.stream().filter(Result::passed).count();
        StringBuilder sb = new StringBuilder()
            .append("\n┌─ ").append(layer).append(" EVAL ")
            .append(passed).append('/').append(results.size())
            .append(" passed (").append(Math.round(passRate() * 100)).append("%)\n");
        for (Result r : results) {
            sb.append("│ ").append(r.passed() ? "✓" : "✗").append("  ")
              .append(r.name()).append("  —  ").append(r.detail()).append('\n');
            for (String f : r.failures()) {
                sb.append("│      · ").append(f).append('\n');
            }
        }
        return sb.append("└─").toString();
    }
}
