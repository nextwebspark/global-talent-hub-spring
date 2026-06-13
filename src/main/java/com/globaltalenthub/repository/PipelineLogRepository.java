package com.globaltalenthub.repository;

import com.globaltalenthub.entity.PipelineLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PipelineLogRepository extends JpaRepository<PipelineLog, Long> {

    List<PipelineLog> findBySearchQueryId(Long searchQueryId);
}
