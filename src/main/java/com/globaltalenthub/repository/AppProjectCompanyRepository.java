package com.globaltalenthub.repository;

import com.globaltalenthub.entity.AppProjectCompany;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Org-scoped access to a project's universe rows. */
public interface AppProjectCompanyRepository extends JpaRepository<AppProjectCompany, Long> {

    List<AppProjectCompany> findByProjectIdAndOrgId(Long projectId, UUID orgId);

    Page<AppProjectCompany> findByProjectIdAndOrgId(Long projectId, UUID orgId, Pageable pageable);

    long countByProjectIdAndOrgId(Long projectId, UUID orgId);

    Optional<AppProjectCompany> findByProjectIdAndCompanyIdAndOrgId(Long projectId, Long companyId, UUID orgId);

    void deleteByProjectIdAndOrgId(Long projectId, UUID orgId);
}
