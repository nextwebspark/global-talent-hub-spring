package com.globaltalenthub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.globaltalenthub.service.pipeline.LlmClassifier;
import com.globaltalenthub.service.pipeline.PipelineUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sector taxonomy normalization + name-based inference. Port of sectorInference.ts.
 * The category lookup is pure; inference falls back through an {@link LlmClassifier}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SectorService {

    public record SectorResult(String sector, String category) {}

    private static final Map<String, List<String>> SECTOR_TAXONOMY = new LinkedHashMap<>();
    static {
        SECTOR_TAXONOMY.put("Energy", List.of("Oil, Gas & Pipelines", "Renewable Energy"));
        SECTOR_TAXONOMY.put("Materials", List.of("Metals & Mining", "Chemicals", "Construction Materials"));
        SECTOR_TAXONOMY.put("Industrials", List.of("Aerospace & Defense", "Transportation & Logistics", "Construction & Engineering", "Industrial Machinery"));
        SECTOR_TAXONOMY.put("Consumer Discretionary", List.of("Retail & E-Commerce", "Automotive", "Travel, Leisure & Hospitality", "Media & Entertainment"));
        SECTOR_TAXONOMY.put("Consumer Staples", List.of("Food & Beverage", "Household & Personal Products", "Grocery & Drug Retail"));
        SECTOR_TAXONOMY.put("Health Care", List.of("Pharmaceuticals & Biotech", "Medical Devices & Equipment", "Health Care Services"));
        SECTOR_TAXONOMY.put("Financial Services", List.of("Banking", "Insurance", "Asset Management", "Fintech & Payments"));
        SECTOR_TAXONOMY.put("Information Technology", List.of("Software & SaaS", "Hardware & Semiconductors", "IT Services & Consulting", "Cybersecurity"));
        SECTOR_TAXONOMY.put("Communication Services", List.of("Telecom", "Internet & Digital Platforms", "Gaming"));
        SECTOR_TAXONOMY.put("Utilities", List.of("Electric Utilities", "Water & Waste Management", "Gas Distribution"));
        SECTOR_TAXONOMY.put("Real Estate", List.of("Commercial Real Estate", "Residential Real Estate", "REITs & Property Management"));
        SECTOR_TAXONOMY.put("Conglomerates & Holding Companies", List.of("Family Conglomerates", "Sovereign & State-Owned Holding Companies", "Private Equity & Investment Holding"));
        SECTOR_TAXONOMY.put("Sovereign Wealth & Government", List.of("Sovereign Wealth Funds", "Government & Public Sector", "Quasi-Government Entities"));
    }

    private static final Map<String, String> SECTOR_TO_CATEGORY = new LinkedHashMap<>();
    static {
        SECTOR_TAXONOMY.forEach((category, sectors) -> sectors.forEach(s -> SECTOR_TO_CATEGORY.put(s, category)));
    }

    private static final String SYSTEM_PROMPT = buildSystemPrompt();

    private final LlmClassifier classifier;

    public static String getCategoryForSector(String sector) {
        if (sector == null) return null;
        return SECTOR_TO_CATEGORY.get(sector);
    }

    public static boolean isStandardSector(String sector) {
        return sector != null && SECTOR_TO_CATEGORY.containsKey(sector);
    }

    /** Keep a valid sub-sector unchanged (+ its category), else infer from the name. */
    public SectorResult normalizeOrInfer(String companyName, String rawSector) {
        if (isStandardSector(rawSector)) {
            return new SectorResult(rawSector, SECTOR_TO_CATEGORY.get(rawSector));
        }
        return inferSector(companyName);
    }

    public SectorResult inferSector(String companyName) {
        if (companyName == null || companyName.isBlank()
            || companyName.equalsIgnoreCase("imported contacts")
            || companyName.equalsIgnoreCase("unknown")) {
            return new SectorResult(null, null);
        }
        try {
            String raw = classifier.classify(SYSTEM_PROMPT + "\n\nCompany: " + companyName
                + "\n\nReturn JSON: {\"sector\": \"...\", \"category\": \"...\"}");
            JsonNode node = PipelineUtils.parseJsonSafe(raw);
            if (node != null && node.hasNonNull("sector")) {
                String sector = node.get("sector").asText();
                if (SECTOR_TO_CATEGORY.containsKey(sector)) {
                    return new SectorResult(sector, SECTOR_TO_CATEGORY.get(sector));
                }
            }
        } catch (Exception e) {
            log.warn("[SectorInference] failed for \"{}\": {}", companyName, e.getMessage());
        }
        return new SectorResult(null, null);
    }

    private static String buildSystemPrompt() {
        StringBuilder list = new StringBuilder();
        SECTOR_TAXONOMY.forEach((cat, subs) -> list.append(cat).append(": ").append(String.join(", ", subs)).append("\n"));
        return "You are a sector classification expert. Classify companies into the most specific sector from this taxonomy:\n\n"
            + list.toString().stripTrailing()
            + "\n\nRules:\n- Respond ONLY with valid JSON, no explanation.\n"
            + "- \"sector\" must be EXACTLY one of the specific sub-sectors listed above.\n"
            + "- \"category\" must be the corresponding category name.";
    }
}
