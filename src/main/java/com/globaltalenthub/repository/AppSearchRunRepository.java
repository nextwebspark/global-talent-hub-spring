package com.globaltalenthub.repository;

import com.globaltalenthub.entity.AppSearchRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Org-scoped access to search runs. */
public interface AppSearchRunRepository extends JpaRepository<AppSearchRun, Long> {

    Optional<AppSearchRun> findByIdAndOrgId(Long id, UUID orgId);
}
