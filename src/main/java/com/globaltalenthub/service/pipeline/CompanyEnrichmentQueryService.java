package com.globaltalenthub.service.pipeline;

import com.globaltalenthub.repository.CompanyEnrichmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Seed-list query over company_enrichment — port of
 * DatabaseStorage.queryEnrichedCompanies.
 *
 * <p>Builds a bounded candidate pool via OR-of-crucial-signals (primary sector in
 * the requested set, or sub-tag overlap), then scores every row in Java and ranks
 * by match score (confidence as tie-breaker), returning the top {@code limit}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyEnrichmentQueryService {

    private final CompanyEnrichmentRepository repository;

    public List<EnrichedCompanyMatch> query(EnrichmentFilter filter, int limit) {
        List<String> sectorAll = new ArrayList<>(filter.primarySectors());
        sectorAll.addAll(filter.adjacentSectors());

        boolean hasSector = !sectorAll.isEmpty();
        boolean hasSubTags = !filter.subTags().isEmpty();

        // No crucial signal at all → nothing meaningful to match. Return empty
        // rather than scanning the table at score 0. (Mirrors the orTerms guard.)
        if (!hasSector && !hasSubTags) {
            return List.of();
        }

        // Bounded, quality-biased candidate pool — never scans the whole table.
        int pool = Math.min(500, Math.max(limit * 5, 100));

        List<Object[]> rawRows = repository.queryCandidatePool(
            hasSector, toArrayLiteral(sectorAll),
            hasSubTags, toArrayLiteral(filter.subTags()),
            toArrayLiteral(filter.countries()),
            pool);

        return rawRows.stream()
            .map(CompanyEnrichmentQueryService::mapRow)
            .map(row -> new EnrichedCompanyMatch(row, CompanyScore.score(row.toScorable(), filter)))
            .sorted(Comparator
                .comparingInt(EnrichedCompanyMatch::matchScore).reversed()
                .thenComparing(m -> m.row().confidence() == null ? BigDecimal.ZERO : m.row().confidence(),
                    Comparator.reverseOrder()))
            .limit(limit)
            .toList();
    }

    /**
     * Build a Postgres array literal {@code {"a","b"}} with each element double-quoted
     * and inner quotes/backslashes escaped — required because sector names contain
     * commas, ampersands and slashes that would otherwise break the literal.
     */
    static String toArrayLiteral(List<String> values) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            String escaped = values.get(i).replace("\\", "\\\\").replace("\"", "\\\"");
            sb.append('"').append(escaped).append('"');
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof String[] arr) return Arrays.asList(arr);
        if (value instanceof Object[] arr) {
            List<String> out = new ArrayList<>(arr.length);
            for (Object o : arr) out.add(o == null ? null : o.toString());
            return out;
        }
        if (value instanceof java.sql.Array sqlArray) {
            try {
                Object inner = sqlArray.getArray();
                if (inner instanceof Object[] arr) {
                    List<String> out = new ArrayList<>(arr.length);
                    for (Object o : arr) out.add(o == null ? null : o.toString());
                    return out;
                }
            } catch (Exception e) {
                log.warn("[EnrichedQuery] failed to read sql array: {}", e.getMessage());
            }
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(o == null ? null : o.toString());
            return out;
        }
        return List.of();
    }

    private static Integer toInteger(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        return new BigDecimal(v.toString());
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /** Map the 23-column projection (column order matches the SELECT). */
    static EnrichedRow mapRow(Object[] r) {
        return new EnrichedRow(
            toLong(r[0]),               // id
            str(r[1]),                  // company_id
            str(r[2]),                  // company_name
            str(r[3]),                  // slug
            str(r[4]),                  // country
            str(r[5]),                  // primary_sector
            toStringList(r[6]),         // sector_tags
            toStringList(r[7]),         // sub_tags
            toStringList(r[8]),         // keywords
            str(r[9]),                  // tagline
            str(r[10]),                 // business_description
            str(r[11]),                 // employee_band
            toInteger(r[12]),           // employee_count_estimate
            str(r[13]),                 // revenue_band
            toLong(r[14]),              // revenue_estimate_usd
            (Boolean) r[15],            // is_listed
            str(r[16]),                 // hq_city
            toBigDecimal(r[17]),        // confidence
            str(r[18]),                 // website
            str(r[19]),                 // phone
            str(r[20]),                 // email
            str(r[21]),                 // address
            PipelineUtils.parseSectorMix(str(r[22])));  // sector_mix (jsonb as text)
    }
}
