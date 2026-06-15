package com.globaltalenthub.repository;

import com.globaltalenthub.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    List<Company> findByOrgId(UUID orgId);

    Optional<Company> findByIdAndOrgId(Long id, UUID orgId);

    List<Company> findBySearchQueryIdAndOrgId(Long searchQueryId, UUID orgId);

    Optional<Company> findByNameIgnoreCaseAndOrgId(String name, UUID orgId);

    Optional<Company> findByNameIgnoreCaseAndSearchQueryIdAndOrgId(String name, Long searchQueryId, UUID orgId);

    @Query("SELECT c FROM Company c WHERE c.name ILIKE %:name% AND c.orgId = :orgId")
    List<Company> searchByName(@Param("name") String name, @Param("orgId") UUID orgId);

    // Ownership filter for add-to-project: only company IDs belonging to the session.
    List<Company> findByIdInAndSearchSessionIdAndOrgId(List<Long> ids, String searchSessionId, UUID orgId);
}
