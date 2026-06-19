package com.globaltalenthub.repository;

import com.globaltalenthub.entity.CompanyEnrichment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CompanyEnrichmentRepository extends JpaRepository<CompanyEnrichment, Long> {

    Optional<CompanyEnrichment> findByCompanyId(String companyId);

    /**
     * Candidate-pool query — mirrors DatabaseStorage.queryEnrichedCompanies.
     *
     * <p>A row is a candidate on ANY crucial signal: its primary sector is in the
     * requested set (primaries + adjacents), OR its sector_tags overlap that set
     * (secondary / broad-sector recall), OR its sub_tags overlap the requested
     * sub_tags. Soft signals (country / revenue / employee / listing) are NOT
     * filtered here — they only affect the Java match score. When countries are requested,
     * country-matching rows are ordered into the pool FIRST (then by data-quality confidence),
     * so a thin requested country is not crowded out of the bounded pool by higher-confidence
     * rows in other countries before the country-aware scorer runs. Final ranking is by match
     * score in {@code CompanyEnrichmentQueryService}.
     *
     * <p>Returns a column projection ({@code Object[]}) of exactly the 23 fields the
     * scorer and row-mapping need, so Hibernate never touches the other non-null jsonb
     * columns (sources/raw_response). {@code sector_mix} is selected as {@code ::text}
     * and parsed in the mapper. Array params are passed as Postgres array literals
     * (e.g. {@code {"Banking & Financial Services"}}).
     */
    @Query(value = """
        SELECT id, company_id, company_name, slug, country, primary_sector, sector_tags, sub_tags,
               keywords, tagline, business_description, employee_band, employee_count_estimate,
               revenue_band, revenue_estimate_usd, is_listed, hq_city, confidence,
               website, phone, email, address, sector_mix::text
        FROM company_enrichment
        WHERE (
          (CAST(:hasSector AS boolean) AND primary_sector = ANY(CAST(:sectorAll AS text[])))
          OR (CAST(:hasSector AS boolean) AND sector_tags && CAST(:sectorAll AS text[]))
          OR (CAST(:hasSubTags AS boolean) AND sub_tags && CAST(:subTags AS text[]))
        )
        ORDER BY (country = ANY(CAST(:countries AS text[]))) DESC, confidence DESC
        LIMIT :pool
        """, nativeQuery = true)
    List<Object[]> queryCandidatePool(
        @Param("hasSector") boolean hasSector,
        @Param("sectorAll") String sectorAll,
        @Param("hasSubTags") boolean hasSubTags,
        @Param("subTags") String subTags,
        @Param("countries") String countries,
        @Param("pool") int pool
    );
}
