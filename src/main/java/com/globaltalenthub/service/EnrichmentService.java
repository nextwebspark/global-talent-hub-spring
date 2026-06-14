package com.globaltalenthub.service;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.globaltalenthub.entity.Company;
import com.globaltalenthub.entity.Executive;
import com.globaltalenthub.repository.CompanyRepository;
import com.globaltalenthub.repository.ExecutiveRepository;
import com.globaltalenthub.repository.SearchQueryRepository;
import com.globaltalenthub.service.pipeline.LlmClassifier;
import com.globaltalenthub.service.pipeline.PipelineUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Import a Clockwork candidate into a project: research the company (LLM), find or
 * create it, then idempotently attach the executive by clockworkId. Port of the
 * import-candidate path in enrichment.ts.
 *
 * <p>The deep candidate-matching orchestration (match / confirm / create-from-clockwork)
 * spans the Clockwork orchestrate pipeline and requires live Clockwork credentials; it is
 * a documented follow-up and is surfaced as NOT_IMPLEMENTED by the controller.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnrichmentService {

    private static final String RESEARCH_PROMPT = """
        Research the company named below and return ONLY JSON with keys:
        name, sector, region, country, streetAddress, latitude (number), longitude (number),
        revenue (number or null), revenueSource, employees (integer or null), employeesSource,
        confidence (1-10 integer). If the company is unknown, return {"name":"Unknown"}.

        Company:\s""";

    private final SearchQueryRepository searchQueryRepo;
    private final CompanyRepository companyRepo;
    private final ExecutiveRepository executiveRepo;
    private final SectorService sectorService;
    private final LlmClassifier classifier;

    public record ImportResult(Long executiveId, Long companyId, boolean alreadyExisted) {}

    @Transactional
    public ImportResult importCandidate(Map<String, Object> body, UUID orgId) {
        Long searchId = asLong(body.get("searchId"));
        String clockworkId = asString(body.get("clockworkId"));
        String name = asString(body.get("name"));
        if (searchId == null || clockworkId == null || name == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "searchId, clockworkId, and name are required");
        }
        searchQueryRepo.findByIdAndOrgId(searchId, orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Search not found"));

        // Idempotency: if this clockworkId already exists, return it.
        Executive existing = executiveRepo.findByClockworkId(clockworkId).orElse(null);
        if (existing != null) {
            return new ImportResult(existing.getId(), existing.getCompanyId(), true);
        }

        String companyName = asString(body.get("company"));
        List<Company> inSearch = companyRepo.findBySearchQueryIdAndOrgId(searchId, orgId);
        Company target = companyName == null ? null : inSearch.stream()
            .filter(c -> c.getName() != null && c.getName().equalsIgnoreCase(companyName))
            .findFirst().orElse(null);

        if (target == null && companyName != null) {
            target = researchAndCreate(companyName, searchId, orgId);
        }
        if (target == null) {
            target = inSearch.isEmpty() ? null : inSearch.get(0);
        }
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "No company available to attach executive. Run a search first.");
        }

        Executive e = new Executive();
        e.setCompanyId(target.getId());
        e.setOrgId(orgId);
        e.setName(name);
        e.setTitle(asString(body.get("title")));
        e.setEmail(asString(body.get("email")));
        e.setLinkedin(asString(body.get("linkedin")));
        e.setImageUrl(asString(body.get("imageUrl")));
        e.setClockworkId(clockworkId);
        e.setClockworkProjectId(asString(body.get("clockworkProjectId")));
        Executive saved = executiveRepo.save(e);
        return new ImportResult(saved.getId(), target.getId(), false);
    }

    private Company researchAndCreate(String companyName, Long searchId, UUID orgId) {
        try {
            JsonNode r = PipelineUtils.parseJsonSafe(classifier.classify(RESEARCH_PROMPT + companyName));
            if (r == null || !r.hasNonNull("name") || "Unknown".equalsIgnoreCase(r.get("name").asText())) {
                log.info("[Import] Company research failed for \"{}\" — using fallback", companyName);
                return null;
            }
            Company c = new Company();
            c.setName(r.get("name").asText());
            SectorService.SectorResult sr = sectorService.normalizeOrInfer(
                c.getName(), r.path("sector").asText(null));
            c.setSector(sr.sector() != null ? sr.sector() : r.path("sector").asText(null));
            c.setSectorCategory(sr.category());
            c.setRegion(r.path("region").asText(null));
            c.setCountry(r.path("country").asText(null));
            c.setStreetAddress(r.path("streetAddress").asText(null));
            if (r.hasNonNull("latitude")) c.setLatitude(BigDecimal.valueOf(r.get("latitude").asDouble()));
            if (r.hasNonNull("longitude")) c.setLongitude(BigDecimal.valueOf(r.get("longitude").asDouble()));
            if (r.hasNonNull("revenue")) c.setRevenue(BigDecimal.valueOf(r.get("revenue").asLong()));
            if (r.hasNonNull("employees")) c.setEmployees(r.get("employees").asInt());
            if (r.hasNonNull("confidence")) c.setConfidence(r.get("confidence").asInt());
            c.setSearchQueryId(searchId);
            c.setOrgId(orgId);
            return companyRepo.save(c);
        } catch (Exception e) {
            log.warn("[Import] Company research error for \"{}\": {}", companyName, e.getMessage());
            return null;
        }
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.valueOf(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }
}
