package com.globaltalenthub.repository;

import com.globaltalenthub.entity.AppCompany;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Master-catalog reads. No org-scoped finders — {@code app_companies} is a shared
 * read-only master (no {@code org_id}). Portal write tables get their own repos.
 *
 * <p>{@link JpaSpecificationExecutor} powers the composable filter search
 * (see {@code AppCompanySpecs}); facet counts are grouped-count native queries.
 */
public interface AppCompanyRepository
        extends JpaRepository<AppCompany, Long>, JpaSpecificationExecutor<AppCompany> {

    /** First catalog company whose domain case-insensitively equals {@code domain} (lowest id wins). */
    @Query("SELECT c FROM AppCompany c WHERE LOWER(c.domain) = LOWER(:domain) ORDER BY c.id ASC")
    List<AppCompany> findByDomainIgnoreCase(@Param("domain") String domain);

    /** {@code (value, count)} pair for a facet bucket. */
    interface FacetRow {
        String getValue();
        long getCount();
    }

    @Query(value = "SELECT primary_industry AS value, COUNT(*) AS count FROM app_companies "
        + "WHERE primary_industry IS NOT NULL GROUP BY primary_industry ORDER BY count DESC",
        nativeQuery = true)
    List<FacetRow> facetIndustries();

    @Query(value = "SELECT hq_country AS value, COUNT(*) AS count FROM app_companies "
        + "WHERE hq_country IS NOT NULL GROUP BY hq_country ORDER BY count DESC",
        nativeQuery = true)
    List<FacetRow> facetCountries();

    @Query(value = "SELECT revenue_range AS value, COUNT(*) AS count FROM app_companies "
        + "WHERE revenue_range IS NOT NULL GROUP BY revenue_range ORDER BY count DESC",
        nativeQuery = true)
    List<FacetRow> facetRevenueRanges();

    @Query(value = "SELECT employee_range AS value, COUNT(*) AS count FROM app_companies "
        + "WHERE employee_range IS NOT NULL GROUP BY employee_range ORDER BY count DESC",
        nativeQuery = true)
    List<FacetRow> facetEmployeeRanges();
}
