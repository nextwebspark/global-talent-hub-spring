package com.globaltalenthub.service;

import java.util.UUID;

import com.globaltalenthub.entity.Company;
import com.globaltalenthub.entity.Executive;
import com.globaltalenthub.entity.PipelineLog;
import com.globaltalenthub.repository.CompanyRepository;
import com.globaltalenthub.repository.ExecutiveRepository;
import com.globaltalenthub.repository.PipelineLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Non-destructive upsert — the core business invariant.
 * Port of DatabaseStorage.upsertCompanyNonDestructive.
 *
 * <p>Confidence-weighted merge over the fields the pipeline supplies:
 * <ul>
 *   <li>field in {@code manuallyEditedFields} → never written (decision "skipped");</li>
 *   <li>existing value null/empty → filled (decision "updated");</li>
 *   <li>existing populated AND new confidence &gt; existing → overwritten, old value
 *       pushed to provenance history (decision "updated");</li>
 *   <li>otherwise kept (decision "kept").</li>
 * </ul>
 * Every decision is logged best-effort to {@code hak_pipeline_log}; provenance is
 * tracked in {@code data_provenance}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyService {

    private static final int DEFAULT_CONFIDENCE = 5;

    private final CompanyRepository companyRepo;
    private final PipelineLogRepository pipelineLogRepo;
    private final ExecutiveRepository executiveRepo;
    private final CoordinateFallbackService coordinateFallbackService;
    private final SectorService sectorService;

    public record UpsertResult(Company company, boolean isNew) {}

    /** Company plus its executives — the shape the React client consumes. */
    public record CompanyWithExecutives(Company company, List<Executive> executives) {}

    // ── Manual CRUD (UI layer) ─────────────────────────────────────────────────

    public java.util.List<CompanyWithExecutives> getAllWithExecutives(UUID orgId) {
        return companyRepo.findByOrgId(orgId).stream()
            .map(c -> new CompanyWithExecutives(c, executiveRepo.findByCompanyIdAndOrgId(c.getId(), orgId)))
            .toList();
    }

    public CompanyWithExecutives getWithExecutives(Long id, UUID orgId) {
        Company c = companyRepo.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Company not found"));
        return new CompanyWithExecutives(c, executiveRepo.findByCompanyIdAndOrgId(id, orgId));
    }

    public java.util.List<Company> searchByName(String name, UUID orgId) {
        if (name == null || name.trim().length() < 2) return java.util.List.of();
        return companyRepo.searchByName(name.trim(), orgId);
    }

    @Transactional
    public Company createManual(Map<String, Object> body, UUID orgId) {
        Company company = new Company();
        applyPatch(company, body);
        company.setOrgId(orgId);
        // Coordinate fallback when no explicit coords.
        if (company.getLatitude() == null && company.getLongitude() == null) {
            CoordinateFallbackService.Result coord =
                coordinateFallbackService.apply(company.getRegion(), company.getCountry(), null, null);
            company.setLatitude(coord.latitude());
            company.setLongitude(coord.longitude());
            if (company.getLocationPrecision() == null) company.setLocationPrecision(coord.locationPrecision());
        }
        SectorService.SectorResult sr = sectorService.normalizeOrInfer(
            company.getName() == null ? "" : company.getName(), company.getSector());
        if (sr.sector() != null) company.setSector(sr.sector());
        company.setSectorCategory(sr.category());
        return companyRepo.save(company);
    }

    @Transactional
    public Company updateManual(Long id, Map<String, Object> patch, UUID orgId) {
        Company c = companyRepo.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Company not found"));
        applyPatch(c, patch);
        if (patch.containsKey("sector")) {
            c.setSectorCategory(SectorService.getCategoryForSector(c.getSector()));
        }
        // Re-derive coordinates when country changed and no explicit coords supplied.
        boolean countryChanged = patch.containsKey("country");
        boolean explicitCoords = patch.containsKey("latitude") && patch.containsKey("longitude");
        if (countryChanged && !explicitCoords && c.getCountry() != null) {
            CoordinateFallbackService.Result coord =
                coordinateFallbackService.apply(c.getRegion(), c.getCountry(), null, null);
            if (coord.latitude() != null) {
                c.setLatitude(coord.latitude());
                c.setLongitude(coord.longitude());
            }
        }
        return companyRepo.save(c);
    }

    @Transactional
    public void delete(Long id, UUID orgId) {
        Company c = companyRepo.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Company not found"));
        companyRepo.delete(c);
    }

    // Apply a partial patch from the UI — only the fields the manual editor sends.
    private void applyPatch(Company c, Map<String, Object> patch) {
        patch.forEach((key, value) -> {
            switch (key) {
                case "name" -> c.setName((String) value);
                case "sector" -> c.setSector((String) value);
                case "businessType" -> c.setBusinessType((String) value);
                case "country" -> c.setCountry((String) value);
                case "region" -> c.setRegion((String) value);
                case "geography" -> c.setGeography((String) value);
                case "streetAddress" -> c.setStreetAddress((String) value);
                case "website" -> c.setWebsite((String) value);
                case "summary" -> c.setSummary((String) value);
                case "companySize" -> c.setCompanySize((String) value);
                case "revenueRange" -> c.setRevenueRange((String) value);
                case "latitude" -> c.setLatitude(toBigDecimal(value));
                case "longitude" -> c.setLongitude(toBigDecimal(value));
                case "revenue" -> c.setRevenue(toBigDecimal(value));
                case "employees" -> c.setEmployees(toInt(value));
                default -> { /* ignore unknown keys */ }
            }
        });
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Field registry ───────────────────────────────────────────────────────
    // The fields the enrichment pipeline supplies (camelCase keys match provenance
    // + fieldConfidences keys). String fields go through the generic merge; revenue
    // / employees are handled specially because they carry their confidence in a
    // dedicated column. `name` is the identity key and is never merged.
    private record FieldAccessor(Function<Company, String> get, BiConsumer<Company, String> set) {}

    private static final Map<String, FieldAccessor> STRING_FIELDS = new LinkedHashMap<>();
    static {
        reg("sector", Company::getSector, Company::setSector);
        reg("sectorCategory", Company::getSectorCategory, Company::setSectorCategory);
        reg("businessType", Company::getBusinessType, Company::setBusinessType);
        reg("region", Company::getRegion, Company::setRegion);
        reg("country", Company::getCountry, Company::setCountry);
        reg("streetAddress", Company::getStreetAddress, Company::setStreetAddress);
        reg("locationPrecision", Company::getLocationPrecision, Company::setLocationPrecision);
        reg("revenueCurrency", Company::getRevenueCurrency, Company::setRevenueCurrency);
        reg("revenueRange", Company::getRevenueRange, Company::setRevenueRange);
        reg("companySize", Company::getCompanySize, Company::setCompanySize);
        reg("summary", Company::getSummary, Company::setSummary);
        reg("website", Company::getWebsite, Company::setWebsite);
        reg("relevanceType", Company::getRelevanceType, Company::setRelevanceType);
        reg("relevanceRationale", Company::getRelevanceRationale, Company::setRelevanceRationale);
        reg("geography", Company::getGeography, Company::setGeography);
    }

    private static void reg(String key, Function<Company, String> get, BiConsumer<Company, String> set) {
        STRING_FIELDS.put(key, new FieldAccessor(get, set));
    }

    @Transactional
    public UpsertResult upsertNonDestructive(Company incoming, Long searchQueryId, UUID orgId,
                                             Map<String, Integer> fieldConfidences) {
        Map<String, Integer> conf = fieldConfidences == null ? Map.of() : fieldConfidences;
        Optional<Company> existingOpt = companyRepo
            .findByNameIgnoreCaseAndSearchQueryIdAndOrgId(incoming.getName(), searchQueryId, orgId);

        // Ordered map of the non-empty fields the pipeline actually supplied.
        LinkedHashMap<String, Object> incomingFields = extractIncomingFields(incoming);

        if (existingOpt.isEmpty()) {
            Map<String, Object> provenance = new HashMap<>();
            incomingFields.forEach((k, v) ->
                provenance.put(k, provEntry(String.valueOf(v), conf.getOrDefault(k, DEFAULT_CONFIDENCE), null)));
            incoming.setSearchQueryId(searchQueryId);
            incoming.setOrgId(orgId);
            incoming.setDataProvenance(provenance);
            log.info("[NonDestructive] Creating new company: \"{}\"", incoming.getName());
            return new UpsertResult(companyRepo.save(incoming), true);
        }

        Company c = existingOpt.get();
        Set<String> manual = c.getManuallyEditedFields() == null ? Set.of() : Set.of(c.getManuallyEditedFields());
        Map<String, Object> existingProv = c.getDataProvenance() == null ? new HashMap<>() : new HashMap<>(c.getDataProvenance());
        Map<String, Object> newProv = new HashMap<>(existingProv);
        boolean changed = false;

        for (var entry : incomingFields.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Object existingValue = currentValue(c, key);

            if (manual.contains(key)) {
                logDecision(c.getName(), key, str(existingValue), String.valueOf(value),
                    "skipped", "field is manually edited — sacred", searchQueryId);
                continue;
            }

            int newConfidence = conf.getOrDefault(key, DEFAULT_CONFIDENCE);
            int existingConfidence = existingConfidenceFor(c, key, existingProv);

            if (isEmpty(existingValue)) {
                applyValue(c, key, value);
                changed = true;
                newProv.put(key, provEntry(String.valueOf(value), newConfidence, null));
                logDecision(c.getName(), key, null, String.valueOf(value),
                    "updated", "existing field was null — filled", searchQueryId);
            } else if (newConfidence > existingConfidence) {
                applyValue(c, key, value);
                changed = true;
                List<Object> history = historyOf(existingProv.get(key));
                history.add(Map.of("value", str(existingValue), "confidence", existingConfidence, "replacedAt", nowIso()));
                newProv.put(key, provEntry(String.valueOf(value), newConfidence, history));
                logDecision(c.getName(), key, str(existingValue), String.valueOf(value),
                    "updated", "new confidence " + newConfidence + " > existing confidence " + existingConfidence, searchQueryId);
            } else {
                logDecision(c.getName(), key, str(existingValue), String.valueOf(value),
                    "kept", "existing confidence " + existingConfidence + " >= new confidence " + newConfidence, searchQueryId);
            }
        }

        if (changed) {
            c.setDataProvenance(newProv);
            return new UpsertResult(companyRepo.save(c), false);
        }
        return new UpsertResult(c, false);
    }

    // ── Field plumbing ─────────────────────────────────────────────────────────

    private LinkedHashMap<String, Object> extractIncomingFields(Company in) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        STRING_FIELDS.forEach((key, acc) -> {
            Object v = acc.get().apply(in);
            if (!isEmpty(v)) fields.put(key, v);
        });
        // Numeric / special fields carried directly on the entity.
        putIfPresent(fields, "revenue", in.getRevenue());
        putIfPresent(fields, "employees", in.getEmployees());
        putIfPresent(fields, "latitude", in.getLatitude());
        putIfPresent(fields, "longitude", in.getLongitude());
        putIfPresent(fields, "confidence", in.getConfidence());
        putIfPresent(fields, "confidenceScore", in.getConfidenceScore());
        putIfPresent(fields, "revenueFiscalYear", in.getRevenueFiscalYear());
        return fields;
    }

    private static void putIfPresent(Map<String, Object> fields, String key, Object value) {
        if (!isEmpty(value)) fields.put(key, value);
    }

    private static Object currentValue(Company c, String key) {
        FieldAccessor acc = STRING_FIELDS.get(key);
        if (acc != null) return acc.get().apply(c);
        return switch (key) {
            case "revenue" -> c.getRevenue();
            case "employees" -> c.getEmployees();
            case "latitude" -> c.getLatitude();
            case "longitude" -> c.getLongitude();
            case "confidence" -> c.getConfidence();
            case "confidenceScore" -> c.getConfidenceScore();
            case "revenueFiscalYear" -> c.getRevenueFiscalYear();
            default -> null;
        };
    }

    private static void applyValue(Company c, String key, Object value) {
        FieldAccessor acc = STRING_FIELDS.get(key);
        if (acc != null) {
            acc.set().accept(c, (String) value);
            return;
        }
        switch (key) {
            case "revenue" -> c.setRevenue((BigDecimal) value);
            case "employees" -> c.setEmployees((Integer) value);
            case "latitude" -> c.setLatitude((BigDecimal) value);
            case "longitude" -> c.setLongitude((BigDecimal) value);
            case "confidence" -> c.setConfidence((Integer) value);
            case "confidenceScore" -> c.setConfidenceScore((Integer) value);
            case "revenueFiscalYear" -> c.setRevenueFiscalYear((Integer) value);
            default -> { /* unknown — ignore */ }
        }
    }

    // Revenue/employees carry their confidence in dedicated columns; everything
    // else reads it off the provenance entry (default 0 when absent).
    private static int existingConfidenceFor(Company c, String key, Map<String, Object> existingProv) {
        if ("revenue".equals(key) && c.getRevenueConfidence() != null) return c.getRevenueConfidence();
        if ("employees".equals(key) && c.getEmployeesConfidence() != null) return c.getEmployeesConfidence();
        Object entry = existingProv.get(key);
        if (entry instanceof Map<?, ?> m && m.get("confidence") instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> historyOf(Object provenanceEntry) {
        if (provenanceEntry instanceof Map<?, ?> m && m.get("history") instanceof List<?> h) {
            return new ArrayList<>((List<Object>) h);
        }
        return new ArrayList<>();
    }

    private static Map<String, Object> provEntry(String value, int confidence, List<Object> history) {
        Map<String, Object> e = new HashMap<>();
        e.put("value", value);
        e.put("confidence", confidence);
        e.put("updatedAt", nowIso());
        e.put("source", "pipeline");
        if (history != null) e.put("history", history);
        return e;
    }

    private void logDecision(String companyName, String fieldName, String oldVal, String newVal,
                             String decision, String reason, Long searchQueryId) {
        try {
            PipelineLog entry = new PipelineLog();
            entry.setCompanyName(companyName);
            entry.setFieldName(fieldName);
            entry.setOldValue(oldVal);
            entry.setNewValue(newVal);
            entry.setDecision(decision);
            entry.setReason(reason);
            entry.setSearchQueryId(searchQueryId);
            pipelineLogRepo.save(entry);
        } catch (Exception e) {
            log.warn("[NonDestructive] Failed to log pipeline decision: {}", e.getMessage());
        }
    }

    private static boolean isEmpty(Object v) {
        return v == null || (v instanceof String s && s.isEmpty());
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    // UTC so provenance history timestamps are stable and comparable across servers.
    private static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }
}
