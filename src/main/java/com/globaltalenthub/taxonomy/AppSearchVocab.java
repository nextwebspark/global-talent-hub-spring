package com.globaltalenthub.taxonomy;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fixed filter vocabulary for the ALAC Talent Map intent extraction, derived from the
 * REAL {@code app_companies} data (a GCC dataset, probed live — see the v2 docs /
 * memory {@code app-companies-real-vocab}).
 *
 * <p>Deliberately NOT reused from {@code taxonomy.Taxonomy}: that is the old SSE
 * pipeline's vocabulary (country names, different revenue cuts, a curated 22-sector
 * list) and does not match this table. Here countries are ISO-2 codes and the bands
 * are the exact strings stored in {@code revenue_range} / {@code employee_range}.
 *
 * <p>Only these three fields are a closed set. {@code industry} is free text
 * ({@code primary_industry} has ~523 distinct labels) — matched by ILIKE in phase-01,
 * never validated here.
 */
public final class AppSearchVocab {

    private AppSearchVocab() {}

    /** {@code hq_country} — 6 ISO-2 codes. */
    public static final Set<String> COUNTRIES = Set.of("AE", "SA", "QA", "KW", "OM", "BH");

    /** {@code revenue_range} — 7 exact band strings. */
    public static final Set<String> REVENUE_RANGES = Set.of(
        "<5M", "5M-25M", "25M-100M", "100M-500M", "500M-1B", "1B-5B", "5B+");

    /** {@code employee_range} — 8 exact band strings (note {@code 501-1000}, not {@code 501-1k}). */
    public static final Set<String> EMPLOYEE_RANGES = Set.of(
        "1-10", "11-50", "51-200", "201-500", "501-1000", "1001-5000", "5001-10000", "10000+");

    /**
     * Common country names/aliases → ISO-2 code. Helps normalize an LLM that returns a
     * name/city instead of a code. Anything unmapped is validated directly against
     * {@link #COUNTRIES} (so a raw {@code "AE"} passes through).
     */
    public static final Map<String, String> COUNTRY_ALIASES = Map.ofEntries(
        Map.entry("united arab emirates", "AE"),
        Map.entry("uae", "AE"),
        Map.entry("emirates", "AE"),
        Map.entry("dubai", "AE"),
        Map.entry("abu dhabi", "AE"),
        Map.entry("saudi arabia", "SA"),
        Map.entry("saudi", "SA"),
        Map.entry("ksa", "SA"),
        Map.entry("riyadh", "SA"),
        Map.entry("jeddah", "SA"),
        Map.entry("qatar", "QA"),
        Map.entry("doha", "QA"),
        Map.entry("kuwait", "KW"),
        Map.entry("oman", "OM"),
        Map.entry("muscat", "OM"),
        Map.entry("bahrain", "BH"),
        Map.entry("manama", "BH"));

    /** Normalize one country token to an ISO-2 code, or {@code null} if it isn't in-vocab. */
    public static String normalizeCountry(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        String upper = t.toUpperCase();
        if (COUNTRIES.contains(upper)) return upper;         // already a code
        String mapped = COUNTRY_ALIASES.get(t.toLowerCase());
        return mapped;                                        // null if unknown
    }

    /** Keep the value only if it is an exact band string; else {@code null} (dropped). */
    public static String validRevenue(String raw) {
        return raw != null && REVENUE_RANGES.contains(raw.trim()) ? raw.trim() : null;
    }

    public static String validEmployee(String raw) {
        return raw != null && EMPLOYEE_RANGES.contains(raw.trim()) ? raw.trim() : null;
    }

    /** The four query-active keys the intent prompt must emit. */
    public static final List<String> QUERY_ACTIVE_KEYS =
        List.of("industry", "country", "revenueRange", "employeeRange");
}
