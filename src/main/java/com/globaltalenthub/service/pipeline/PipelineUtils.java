package com.globaltalenthub.service.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared pipeline helpers — port of server/services/pipeline/utils.ts.
 * Country normalization whitelist + best-effort JSON extraction.
 */
public final class PipelineUtils {

    private PipelineUtils() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Country aliases (lower-case key → canonical name). The LLM never supplies
    // free-form strings to DB queries — this is the whitelist; unknown values drop.
    private static final List<Map.Entry<String, String>> COUNTRY_ALIASES = List.of(
        Map.entry("saudi arabia", "Saudi Arabia"), Map.entry("saudi", "Saudi Arabia"),
        Map.entry("united arab emirates", "United Arab Emirates"), Map.entry("uae", "United Arab Emirates"),
        Map.entry("qatar", "Qatar"), Map.entry("kuwait", "Kuwait"), Map.entry("bahrain", "Bahrain"),
        Map.entry("oman", "Oman"), Map.entry("egypt", "Egypt"), Map.entry("jordan", "Jordan"),
        Map.entry("lebanon", "Lebanon"), Map.entry("iraq", "Iraq"), Map.entry("turkey", "Turkey"),
        Map.entry("united kingdom", "United Kingdom"), Map.entry("uk", "United Kingdom"),
        Map.entry("united states", "United States"), Map.entry("usa", "United States"),
        Map.entry("germany", "Germany"), Map.entry("france", "France"), Map.entry("india", "India"),
        Map.entry("china", "China"), Map.entry("japan", "Japan"), Map.entry("singapore", "Singapore"),
        Map.entry("australia", "Australia"), Map.entry("canada", "Canada"),
        Map.entry("south africa", "South Africa"), Map.entry("nigeria", "Nigeria"),
        Map.entry("brazil", "Brazil"), Map.entry("mexico", "Mexico")
    );

    /**
     * Normalize LLM-supplied country strings to canonical names, dropping anything
     * not in the whitelist (matched case-insensitively against aliases or names).
     */
    public static List<String> normalizeCountries(List<String> countries) {
        Set<String> out = new LinkedHashSet<>();
        if (countries == null) return new ArrayList<>(out);
        for (String raw : countries) {
            if (raw == null) continue;
            String lower = raw.trim().toLowerCase();
            for (var alias : COUNTRY_ALIASES) {
                if (lower.equals(alias.getKey()) || lower.equals(alias.getValue().toLowerCase())) {
                    out.add(alias.getValue());
                    break;
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");

    /**
     * Best-effort JSON object extraction: strips markdown fences and isolates the
     * first {...} block before parsing. Returns null on failure.
     */
    public static JsonNode parseJsonSafe(String content) {
        if (content == null) return null;
        String cleaned = content.trim();
        Matcher m = FENCE.matcher(cleaned);
        if (m.find()) cleaned = m.group(1).trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start != -1 && end != -1 && end >= start) {
            cleaned = cleaned.substring(start, end + 1);
        }
        try {
            return MAPPER.readTree(cleaned);
        } catch (Exception e) {
            return null;
        }
    }
}
