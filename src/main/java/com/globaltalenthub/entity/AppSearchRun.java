package com.globaltalenthub.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One search execution — the durable, explainable origin a project links to.
 * {@code parsed_criteria} (jsonb) is the LLM parse merged with the wizard's edited
 * criteria. Org-scoped. Table from {@code docs/05_app_portal.sql}.
 *
 * <p>jsonb mapping copied from {@link SearchQuery}.
 */
@Entity
@Table(name = "app_search_runs")
@Data
@NoArgsConstructor
public class AppSearchRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(nullable = false)
    private String query;

    private String mode;

    @Column(name = "parsed_criteria", columnDefinition = "jsonb")
    @Type(JsonBinaryType.class)
    private Map<String, Object> parsedCriteria;

    @Column(name = "result_count")
    private Integer resultCount;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (status == null) status = "active";
        if (mode == null) mode = "Search";   // column is NOT NULL DEFAULT 'Search' in DDL
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
