package com.globaltalenthub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.globaltalenthub.entity.Remuneration;
import com.globaltalenthub.service.pipeline.LlmClassifier;
import com.globaltalenthub.service.pipeline.PipelineUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Parse free-text remuneration notes into a structured {@link Remuneration} via the
 * LLM. Port of remunerationParser.ts. Returns empty on parse failure (never throws).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RemunerationParserService {

    private static final String PROMPT = """
        Extract structured executive compensation from the text below. Amounts are numeric (no currency symbols).
        Return ONLY JSON with keys: baseSalary, housingAllowance, transportAllowance, schoolingAllowance,
        totalAllowances, bonus, longTermIncentives (numbers or null), currency (3-letter or null),
        year (integer or null), notes (string or null).

        TEXT:
        """;

    private final LlmClassifier classifier;

    public Optional<Remuneration> parse(String text, Long executiveId) {
        if (text == null || text.trim().length() < 5) return Optional.empty();
        try {
            JsonNode node = PipelineUtils.parseJsonSafe(classifier.classify(PROMPT + text));
            if (node == null) return Optional.empty();
            Remuneration r = new Remuneration();
            r.setExecutiveId(executiveId);
            r.setBaseSalary(dec(node, "baseSalary"));
            r.setHousingAllowance(dec(node, "housingAllowance"));
            r.setTransportAllowance(dec(node, "transportAllowance"));
            r.setSchoolingAllowance(dec(node, "schoolingAllowance"));
            r.setTotalAllowances(dec(node, "totalAllowances"));
            r.setBonus(dec(node, "bonus"));
            r.setLongTermIncentives(dec(node, "longTermIncentives"));
            if (node.hasNonNull("currency")) r.setCurrency(node.get("currency").asText());
            if (node.hasNonNull("year")) r.setYear(node.get("year").asText());
            if (node.hasNonNull("notes")) r.setNotes(node.get("notes").asText());
            return Optional.of(r);
        } catch (Exception e) {
            log.warn("[RemunerationParser] parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static BigDecimal dec(JsonNode node, String field) {
        if (!node.hasNonNull(field)) return null;
        try {
            return new BigDecimal(node.get(field).asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
