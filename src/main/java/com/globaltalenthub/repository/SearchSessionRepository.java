package com.globaltalenthub.repository;

import com.globaltalenthub.entity.SearchSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

public interface SearchSessionRepository extends JpaRepository<SearchSession, String> {

    @Modifying
    @Transactional
    @Query(value = "UPDATE hak_search_sessions SET inferred_intent = CAST(:intent AS jsonb), updated_at = NOW() WHERE id = :sessionId",
           nativeQuery = true)
    void updateInferredIntent(@Param("sessionId") String sessionId, @Param("intent") String intentJson);
}
