package com.globaltalenthub.entity;

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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A "search map" workspace — a confirmed universe saved under a client. Belongs to an
 * {@code app_clients} record (NOT a catalog company) and originates from a search run.
 * Org-scoped. Table from {@code docs/05_app_portal.sql}. {@code status} ∈ active|archived.
 */
@Entity
@Table(name = "app_projects")
@Data
@NoArgsConstructor
public class AppProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(nullable = false)
    private String name;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "search_run_id", nullable = false)
    private Long searchRunId;

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
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
