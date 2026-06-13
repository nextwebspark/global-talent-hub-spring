package com.globaltalenthub.taxonomy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TaxonomyTest {

    @Test
    void sectors_containsAllExpected() {
        assertThat(Taxonomy.SECTORS).contains(
            "Banking & Financial Services",
            "Technology & Software",
            "Healthcare & Pharmaceuticals",
            "Conglomerates / Family Groups / Holdings"
        );
        assertThat(Taxonomy.SECTORS).hasSize(22);
    }

    @Test
    void employeeBands_valid() {
        assertThat(Taxonomy.EMPLOYEE_BANDS).contains("1-10", "10k+");
        assertThat(Taxonomy.EMPLOYEE_BANDS).hasSize(8);
    }

    @Test
    void revenueBands_valid() {
        assertThat(Taxonomy.REVENUE_BANDS).contains("<$10M", ">$10B");
        assertThat(Taxonomy.REVENUE_BANDS).hasSize(6);
    }

    @Test
    void subTags_nonEmpty() {
        assertThat(Taxonomy.SUB_TAGS).isNotEmpty();
        assertThat(Taxonomy.SUB_TAGS).contains("retail-banking", "ai-ml-platform", "executive-search");
    }

    @Test
    void adjacentSectorsFor_returnsCorrectAdjacent() {
        List<String> adjacent = Taxonomy.adjacentSectorsFor(List.of("Banking & Financial Services"));
        assertThat(adjacent).contains("Capital Markets & Asset Management", "Insurance", "Technology & Software");
        assertThat(adjacent).doesNotContain("Banking & Financial Services");
    }

    @Test
    void adjacentSectorsFor_dedupes_acrossMultiplePrimaries() {
        // Both Banking and Insurance have Capital Markets as adjacent
        List<String> adjacent = Taxonomy.adjacentSectorsFor(
            List.of("Banking & Financial Services", "Insurance")
        );
        long capMarketsCount = adjacent.stream()
            .filter("Capital Markets & Asset Management"::equals)
            .count();
        assertThat(capMarketsCount).isEqualTo(1);
    }

    @Test
    void adjacentSectorsFor_excludesPrimaries() {
        List<String> primaries = List.of("Banking & Financial Services", "Technology & Software");
        List<String> adjacent = Taxonomy.adjacentSectorsFor(primaries);
        assertThat(adjacent).doesNotContainAnyElementsOf(primaries);
    }

    @Test
    void adjacentSectorsFor_emptyInput_returnsEmpty() {
        assertThat(Taxonomy.adjacentSectorsFor(List.of())).isEmpty();
    }

    @Test
    void sectors_areImmutable() {
        assertThatThrownBy(() -> Taxonomy.SECTORS.add("Fake Sector"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void subTags_containsNoSectorNames() {
        // Sub-tags are kebab-case; sectors have spaces — no overlap expected
        for (String subTag : Taxonomy.SUB_TAGS) {
            assertThat(Taxonomy.SECTORS).doesNotContain(subTag);
        }
    }
}
