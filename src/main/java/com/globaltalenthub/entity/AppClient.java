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
 * An org's own client relationship — who a project is done FOR. Always has a
 * {@code name}; {@code linked_company_id} is an OPTIONAL enrichment link to a catalog
 * {@code app_companies} row (logo/industry/HQ) when the client also happens to be one.
 * Org-scoped. Table from {@code docs/05_app_portal.sql}.
 */
@Entity
@Table(name = "app_clients")
@Data
@NoArgsConstructor
public class AppClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(nullable = false)
    private String name;

    /** Optional web domain (e.g. acme.com); used to auto-match a catalog company by app_companies.domain. */
    @Column(name = "domain")
    private String domain;

    /** Optional FK to app_companies.id (display enrichment); null for a non-catalog client. */
    @Column(name = "linked_company_id")
    private Long linkedCompanyId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
