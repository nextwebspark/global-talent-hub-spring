package com.globaltalenthub.repository;

import com.globaltalenthub.entity.AppClient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Org-scoped access to the org's own client records. */
public interface AppClientRepository extends JpaRepository<AppClient, Long> {

    Optional<AppClient> findByIdAndOrgId(Long id, UUID orgId);

    /** Dedupe a catalog-linked client per org (respects UNIQUE(org_id, linked_company_id)). */
    Optional<AppClient> findByOrgIdAndLinkedCompanyId(UUID orgId, Long linkedCompanyId);

    List<AppClient> findByOrgId(UUID orgId);

    @Query("SELECT c FROM AppClient c WHERE c.orgId = :orgId AND LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%')) ORDER BY c.name")
    List<AppClient> searchByName(@Param("orgId") UUID orgId, @Param("q") String q);
}
