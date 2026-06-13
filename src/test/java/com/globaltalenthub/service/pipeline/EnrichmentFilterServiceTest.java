package com.globaltalenthub.service.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnrichmentFilterServiceTest {

    @Mock
    LlmClassifier classifier;

    @InjectMocks
    EnrichmentFilterService service;

    @Test
    void validJson_keepsOnlyInVocab_dedupes_derivesAdjacent_normalizesCountries() {
        when(classifier.classify(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {
              "primarySectors": ["Banking & Financial Services", "Banking & Financial Services", "Not A Sector"],
              "subTags": ["retail-banking", "made-up-tag"],
              "countries": ["uae", "atlantis"],
              "employeeBands": ["1k-5k", "nope"],
              "revenueBands": ["$1-10B"],
              "isListed": true,
              "searchRationale": "Large banks in the UAE"
            }
            """);

        EnrichmentFilter f = service.extract("big banks uae", null);

        assertThat(f.primarySectors()).containsExactly("Banking & Financial Services");
        assertThat(f.subTags()).containsExactly("retail-banking");
        assertThat(f.countries()).containsExactly("United Arab Emirates");
        assertThat(f.employeeBands()).containsExactly("1k-5k");
        assertThat(f.revenueBands()).containsExactly("$1-10B");
        assertThat(f.isListed()).isTrue();
        // Adjacency derived from taxonomy, not the model.
        assertThat(f.adjacentSectors())
            .containsExactly("Capital Markets & Asset Management", "Insurance", "Technology & Software");
        assertThat(f.searchRationale()).isEqualTo("Large banks in the UAE");
    }

    @Test
    void llmThrows_returnsEmptyFilter_neverThrows() {
        when(classifier.classify(org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new RuntimeException("vertex down"));

        EnrichmentFilter f = service.extract("anything", null);

        assertThat(f.primarySectors()).isEmpty();
        assertThat(f.adjacentSectors()).isEmpty();
        assertThat(f.searchRationale()).isEqualTo("Companies relevant to: anything");
    }

    @Test
    void junkResponse_returnsEmptyFilter() {
        when(classifier.classify(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("I cannot help with that.");

        EnrichmentFilter f = service.extract("foo", null);

        assertThat(f.primarySectors()).isEmpty();
        assertThat(f.searchRationale()).isEqualTo("Companies relevant to: foo");
    }

    @Test
    void nonBooleanIsListed_isNull_blankRationale_fallsBack() {
        when(classifier.classify(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {"primarySectors":["Insurance"],"isListed":"yes","searchRationale":"  "}
            """);

        EnrichmentFilter f = service.extract("insurers", null);

        assertThat(f.isListed()).isNull();
        assertThat(f.searchRationale()).isEqualTo("Companies relevant to: insurers");
    }

    @Test
    void isUnmapped_trueWhenNoSignalAndFallbackRationale() {
        EnrichmentFilter empty = EnrichmentFilter.empty("xyz");
        assertThat(EnrichmentFilterService.isUnmapped(empty, "xyz")).isTrue();
    }

    @Test
    void isUnmapped_falseWhenCustomRationaleEvenIfNoSignal() {
        EnrichmentFilter f = new EnrichmentFilter(
            java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
            java.util.List.of(), java.util.List.of(), null, "Custom rationale");
        assertThat(EnrichmentFilterService.isUnmapped(f, "xyz")).isFalse();
    }

    @Test
    void filterToInferredIntent_mapsFields() {
        EnrichmentFilter f = new EnrichmentFilter(
            java.util.List.of("Insurance"), java.util.List.of("Banking & Financial Services"),
            java.util.List.of("takaful"), java.util.List.of("Qatar"),
            java.util.List.of(), java.util.List.of(), null, "rationale");

        var intent = EnrichmentFilterService.filterToInferredIntent(f);

        assertThat(intent.primarySectors()).containsExactly("Insurance");
        assertThat(intent.targetGeographies()).containsExactly("Qatar");
        assertThat(intent.keyInclusions()).containsExactly("takaful");
        assertThat(intent.commercialRole()).isEqualTo("any");
        assertThat(intent.confidenceScore()).isEqualTo(0.85);
    }
}
