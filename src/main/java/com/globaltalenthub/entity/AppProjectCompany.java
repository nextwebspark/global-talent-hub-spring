package com.globaltalenthub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Join of a project's universe to a master {@code app_companies} row + per-company
 * triage/mapping state. {@code status} ∈ untriaged|in_universe|shortlisted|declined
 * (triage; String, not a JPA enum — matches the DDL CHECK). {@code relevance_type} ∈
 * Direct|Adjacent|AI Inferred (NOT NULL, default Direct). Table from
 * {@code docs/05_app_portal.sql}.
 */
@Entity
@Table(name = "app_project_companies",
    uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "company_id"}))
@Data
@NoArgsConstructor
public class AppProjectCompany {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "relevance_type", nullable = false)
    private String relevanceType;

    private Integer confidence;

    @Column(nullable = false)
    private String status;

    @Column(name = "map_x")
    private BigDecimal mapX;

    @Column(name = "map_y")
    private BigDecimal mapY;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "untriaged";
        if (relevanceType == null) relevanceType = "Direct";
    }
}
