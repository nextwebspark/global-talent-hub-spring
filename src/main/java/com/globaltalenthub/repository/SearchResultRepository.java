package com.globaltalenthub.repository;

import com.globaltalenthub.entity.SearchResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SearchResultRepository extends JpaRepository<SearchResult, Long> {

    List<SearchResult> findBySearchQueryId(Long searchQueryId);
}
