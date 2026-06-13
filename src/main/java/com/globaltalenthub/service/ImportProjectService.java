package com.globaltalenthub.service;

import com.globaltalenthub.entity.Company;
import com.globaltalenthub.entity.Executive;
import com.globaltalenthub.entity.SearchQuery;
import com.globaltalenthub.repository.CompanyRepository;
import com.globaltalenthub.repository.ExecutiveRepository;
import com.globaltalenthub.repository.SearchQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bulk import a project from mapped tabular (Excel/paste) records: create the project,
 * then per record create-or-reuse the company and attach the executive. Port of
 * importProject.ts core ingest (the post-import sector/diversity enrichment is a
 * documented follow-up requiring live LLM keys).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportProjectService {

    private final SearchQueryRepository searchQueryRepo;
    private final CompanyRepository companyRepo;
    private final ExecutiveRepository executiveRepo;

    public record ImportResult(Long searchQueryId, int imported, int skipped, List<String> errors) {}

    @Transactional
    public ImportResult importProject(Map<String, Object> body, String orgId, String userId) {
        Object recordsObj = body.get("records");
        Object mappingsObj = body.get("mappings");
        if (!(recordsObj instanceof List<?> records) || records.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No records provided");
        }
        if (!(mappingsObj instanceof Map<?, ?> mappingsRaw)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No column mappings provided");
        }
        @SuppressWarnings("unchecked")
        Map<String, String> mappings = (Map<String, String>) mappingsRaw;

        String name = body.get("projectName") instanceof String s && !s.isBlank() ? s : "Import";
        SearchQuery sq = new SearchQuery();
        sq.setQuery(name);
        sq.setUniqueKey("import_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 6));
        sq.setOrgId(orgId);
        sq.setCreatedBy(userId);
        sq.setResultCount(0);
        sq.setStatus("active");
        sq = searchQueryRepo.save(sq);

        Map<String, Company> companyByName = new LinkedHashMap<>();
        int imported = 0;
        int skipped = 0;
        List<String> errors = new java.util.ArrayList<>();

        for (Object recObj : records) {
            if (!(recObj instanceof Map<?, ?> record)) {
                skipped++;
                continue;
            }
            try {
                String execName = mappedValue(record, mappings.get("name"));
                String title = orDefault(mappedValue(record, mappings.get("title")), "Executive");
                String companyName = mappedValue(record, mappings.get("company"));
                String country = mappedValue(record, mappings.get("country"));

                if (execName == null && companyName == null) {
                    skipped++;
                    continue;
                }
                String key = companyName == null ? "__no_company__" : companyName.toLowerCase();
                Company company = companyByName.get(key);
                if (company == null) {
                    company = new Company();
                    company.setName(companyName == null ? "Imported Contacts" : companyName);
                    company.setCountry(country);
                    company.setSearchQueryId(sq.getId());
                    company.setOrgId(orgId);
                    BigDecimal revenue = parseNumeric(mappedValue(record, mappings.get("revenue")));
                    if (revenue != null) company.setRevenue(revenue);
                    company = companyRepo.save(company);
                    companyByName.put(key, company);
                }

                if (execName != null) {
                    Executive e = new Executive();
                    e.setCompanyId(company.getId());
                    e.setOrgId(orgId);
                    e.setName(execName);
                    e.setTitle(title);
                    e.setEmail(mappedValue(record, mappings.get("email")));
                    e.setLinkedin(mappedValue(record, mappings.get("linkedin")));
                    executiveRepo.save(e);
                    imported++;
                }
            } catch (Exception ex) {
                // Don't echo internal exception detail to the client; log it server-side.
                log.warn("[ImportProject] row skipped: {}", ex.getMessage());
                errors.add("Row could not be imported");
                skipped++;
            }
        }

        sq.setResultCount(companyByName.size());
        searchQueryRepo.save(sq);
        return new ImportResult(sq.getId(), imported, skipped, errors);
    }

    private static String mappedValue(Map<?, ?> record, String header) {
        if (header == null) return null;
        Object v = record.get(header);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal parseNumeric(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isBlank()) return null;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String orDefault(String v, String d) {
        return v == null ? d : v;
    }
}
