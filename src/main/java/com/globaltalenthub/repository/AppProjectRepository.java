package com.globaltalenthub.repository;

import com.globaltalenthub.entity.AppProject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Org-scoped access to projects. */
public interface AppProjectRepository extends JpaRepository<AppProject, Long> {

    Page<AppProject> findByOrgId(UUID orgId, Pageable pageable);

    Optional<AppProject> findByIdAndOrgId(Long id, UUID orgId);
}
