package com.globaltalenthub.repository;

import com.globaltalenthub.entity.SearchQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface SearchQueryRepository extends JpaRepository<SearchQuery, Long> {

    Optional<SearchQuery> findByIdAndOrgId(Long id, String orgId);

    List<SearchQuery> findByOrgIdOrderByCreatedAtDesc(String orgId);

    Optional<SearchQuery> findByUniqueKeyAndOrgId(String uniqueKey, String orgId);

    // Clockwork project IDs the org has linked to one of its search queries.
    @Query("SELECT q.clockworkProjectId FROM SearchQuery q WHERE q.orgId = :orgId AND q.clockworkProjectId IS NOT NULL")
    List<String> findClockworkProjectIdsByOrgId(@Param("orgId") String orgId);

    boolean existsByClockworkProjectIdAndOrgId(String clockworkProjectId, String orgId);

    @Modifying
    @Transactional
    @Query("UPDATE SearchQuery q SET q.resultCount = :count, q.updatedAt = CURRENT_TIMESTAMP WHERE q.id = :id AND q.orgId = :orgId")
    int updateResultCount(@Param("id") Long id, @Param("count") int count, @Param("orgId") String orgId);
}
