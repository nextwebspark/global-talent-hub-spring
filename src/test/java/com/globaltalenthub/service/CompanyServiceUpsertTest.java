package com.globaltalenthub.service;

import com.globaltalenthub.entity.Company;
import com.globaltalenthub.repository.CompanyRepository;
import com.globaltalenthub.repository.PipelineLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyServiceUpsertTest {

    @Mock
    private CompanyRepository companyRepo;

    @Mock
    private PipelineLogRepository pipelineLogRepo;

    @Mock
    private com.globaltalenthub.repository.ExecutiveRepository executiveRepo;

    @Mock
    private CoordinateFallbackService coordinateFallbackService;

    @Mock
    private SectorService sectorService;

    @InjectMocks
    private CompanyService companyService;

    private static final Long SEARCH_QUERY_ID = 42L;
    private static final String ORG_ID = "org-123";
    private static final Map<String, Integer> PIPELINE_CONF = Map.of("country", 7, "sector", 7);

    @BeforeEach
    void setup() {
        lenient().when(companyRepo.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Company incoming(String name) {
        Company c = new Company();
        c.setName(name);
        return c;
    }

    // ── Invariant case 1: new company ──────────────────────────────────────────
    @Test
    void newCompany_insertsAllFields_withProvenance() {
        when(companyRepo.findByNameIgnoreCaseAndSearchQueryIdAndOrgId(any(), any(), any()))
            .thenReturn(Optional.empty());

        Company in = incoming("Acme Corp");
        in.setSector("Technology & Software");
        in.setCountry("UAE");

        CompanyService.UpsertResult result = companyService.upsertNonDestructive(
            in, SEARCH_QUERY_ID, ORG_ID, PIPELINE_CONF);

        assertThat(result.isNew()).isTrue();
        assertThat(result.company().getSector()).isEqualTo("Technology & Software");
        assertThat(result.company().getOrgId()).isEqualTo(ORG_ID);
        assertThat(result.company().getSearchQueryId()).isEqualTo(SEARCH_QUERY_ID);

        Map<String, Object> prov = result.company().getDataProvenance();
        assertThat(prov).containsKeys("sector", "country");
        @SuppressWarnings("unchecked")
        Map<String, Object> sectorProv = (Map<String, Object>) prov.get("sector");
        assertThat(sectorProv.get("confidence")).isEqualTo(7);          // from fieldConfidences
        assertThat(sectorProv.get("source")).isEqualTo("pipeline");
    }

    // ── Invariant case 2: existing, null field → filled ────────────────────────
    @Test
    void existingCompany_nullField_getsFilled_decisionUpdated() {
        Company existing = incoming("Acme Corp");
        existing.setSector(null);
        existing.setManuallyEditedFields(new String[0]);
        when(companyRepo.findByNameIgnoreCaseAndSearchQueryIdAndOrgId("Acme Corp", SEARCH_QUERY_ID, ORG_ID))
            .thenReturn(Optional.of(existing));

        Company in = incoming("Acme Corp");
        in.setSector("Technology & Software");

        CompanyService.UpsertResult result = companyService.upsertNonDestructive(
            in, SEARCH_QUERY_ID, ORG_ID, PIPELINE_CONF);

        assertThat(result.isNew()).isFalse();
        assertThat(result.company().getSector()).isEqualTo("Technology & Software");
        verify(pipelineLogRepo, atLeastOnce()).save(argThat(l ->
            l.getFieldName().equals("sector") && l.getDecision().equals("updated")
                && l.getReason().contains("null")));
    }

    // ── Invariant case 3: existing populated, lower/equal confidence → kept ─────
    @Test
    void existingCompany_populatedField_lowerConfidence_isKept() {
        Company existing = incoming("Acme Corp");
        existing.setSector("Banking & Financial Services");
        existing.setManuallyEditedFields(new String[0]);
        // existing sector confidence 9 (via provenance) > incoming 7
        Map<String, Object> prov = new HashMap<>();
        prov.put("sector", Map.of("value", "Banking & Financial Services", "confidence", 9));
        existing.setDataProvenance(prov);
        when(companyRepo.findByNameIgnoreCaseAndSearchQueryIdAndOrgId("Acme Corp", SEARCH_QUERY_ID, ORG_ID))
            .thenReturn(Optional.of(existing));

        Company in = incoming("Acme Corp");
        in.setSector("Technology & Software");

        CompanyService.UpsertResult result = companyService.upsertNonDestructive(
            in, SEARCH_QUERY_ID, ORG_ID, PIPELINE_CONF);

        assertThat(result.company().getSector()).isEqualTo("Banking & Financial Services");
        verify(pipelineLogRepo, atLeastOnce()).save(argThat(l ->
            l.getFieldName().equals("sector") && l.getDecision().equals("kept")));
        verify(companyRepo, never()).save(any(Company.class)); // nothing changed
    }

    // ── Confidence overwrite path (the case the old code lacked) ────────────────
    @Test
    void existingCompany_populatedField_higherConfidence_isOverwritten_withHistory() {
        Company existing = incoming("Acme Corp");
        existing.setSector("Banking & Financial Services");
        existing.setManuallyEditedFields(new String[0]);
        Map<String, Object> prov = new HashMap<>();
        prov.put("sector", Map.of("value", "Banking & Financial Services", "confidence", 3));
        existing.setDataProvenance(prov);
        when(companyRepo.findByNameIgnoreCaseAndSearchQueryIdAndOrgId("Acme Corp", SEARCH_QUERY_ID, ORG_ID))
            .thenReturn(Optional.of(existing));

        Company in = incoming("Acme Corp");
        in.setSector("Technology & Software"); // incoming confidence 7 > existing 3

        CompanyService.UpsertResult result = companyService.upsertNonDestructive(
            in, SEARCH_QUERY_ID, ORG_ID, PIPELINE_CONF);

        assertThat(result.company().getSector()).isEqualTo("Technology & Software");
        @SuppressWarnings("unchecked")
        Map<String, Object> sectorProv = (Map<String, Object>) result.company().getDataProvenance().get("sector");
        assertThat(sectorProv.get("confidence")).isEqualTo(7);
        @SuppressWarnings("unchecked")
        List<Object> history = (List<Object>) sectorProv.get("history");
        assertThat(history).hasSize(1);
        verify(pipelineLogRepo, atLeastOnce()).save(argThat(l ->
            l.getFieldName().equals("sector") && l.getDecision().equals("updated")
                && l.getReason().contains("> existing confidence")));
    }

    // ── Invariant case 4: manuallyEditedFields is sacred (even if null) ─────────
    @Test
    void existingCompany_manuallyEditedField_neverWritten_decisionSkipped() {
        Company existing = incoming("Acme Corp");
        existing.setSector(null);
        existing.setManuallyEditedFields(new String[]{"sector"});
        when(companyRepo.findByNameIgnoreCaseAndSearchQueryIdAndOrgId("Acme Corp", SEARCH_QUERY_ID, ORG_ID))
            .thenReturn(Optional.of(existing));

        Company in = incoming("Acme Corp");
        in.setSector("Technology & Software");

        CompanyService.UpsertResult result = companyService.upsertNonDestructive(
            in, SEARCH_QUERY_ID, ORG_ID, PIPELINE_CONF);

        assertThat(result.company().getSector()).isNull();
        verify(pipelineLogRepo, atLeastOnce()).save(argThat(l ->
            l.getFieldName().equals("sector") && l.getDecision().equals("skipped")
                && l.getReason().contains("sacred")));
    }

    // ── revenue confidence sourced from the dedicated column ────────────────────
    @Test
    void revenueConfidence_readFromColumn_notProvenance() {
        Company existing = incoming("Acme Corp");
        existing.setRevenue(new java.math.BigDecimal("1000"));
        existing.setRevenueConfidence(9); // column-based; higher than incoming 7
        existing.setManuallyEditedFields(new String[0]);
        when(companyRepo.findByNameIgnoreCaseAndSearchQueryIdAndOrgId("Acme Corp", SEARCH_QUERY_ID, ORG_ID))
            .thenReturn(Optional.of(existing));

        Company in = incoming("Acme Corp");
        in.setRevenue(new java.math.BigDecimal("2000"));

        CompanyService.UpsertResult result = companyService.upsertNonDestructive(
            in, SEARCH_QUERY_ID, ORG_ID, Map.of("revenue", 7));

        assertThat(result.company().getRevenue()).isEqualByComparingTo("1000"); // kept
        verify(pipelineLogRepo, atLeastOnce()).save(argThat(l ->
            l.getFieldName().equals("revenue") && l.getDecision().equals("kept")));
    }

    // ── logDecision swallows repo failures ──────────────────────────────────────
    @Test
    void logDecision_failure_doesNotBreakUpsert() {
        Company existing = incoming("Acme Corp");
        existing.setCountry(null);
        existing.setManuallyEditedFields(new String[0]);
        when(companyRepo.findByNameIgnoreCaseAndSearchQueryIdAndOrgId("Acme Corp", SEARCH_QUERY_ID, ORG_ID))
            .thenReturn(Optional.of(existing));
        when(pipelineLogRepo.save(any())).thenThrow(new RuntimeException("db down"));

        Company in = incoming("Acme Corp");
        in.setCountry("UAE");

        CompanyService.UpsertResult result = companyService.upsertNonDestructive(
            in, SEARCH_QUERY_ID, ORG_ID, PIPELINE_CONF);

        assertThat(result.company().getCountry()).isEqualTo("UAE"); // upsert still succeeded
    }
}
