package com.globaltalenthub.repository;

import com.globaltalenthub.entity.SearchQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SearchQueryRepository extends JpaRepository<SearchQuery, Long> {

    Optional<SearchQuery> findByIdAndOrgId(Long id, UUID orgId);

    List<SearchQuery> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    Optional<SearchQuery> findByUniqueKeyAndOrgId(String uniqueKey, UUID orgId);

    // Clockwork project IDs the org has linked to one of its search queries.
    @Query("SELECT q.clockworkProjectId FROM SearchQuery q WHERE q.orgId = :orgId AND q.clockworkProjectId IS NOT NULL")
    List<String> findClockworkProjectIdsByOrgId(@Param("orgId") UUID orgId);

    boolean existsByClockworkProjectIdAndOrgId(String clockworkProjectId, UUID orgId);

    @Modifying
    @Transactional
    @Query("UPDATE SearchQuery q SET q.resultCount = :count, q.updatedAt = CURRENT_TIMESTAMP WHERE q.id = :id AND q.orgId = :orgId")
    int updateResultCount(@Param("id") Long id, @Param("count") int count, @Param("orgId") UUID orgId);
}
