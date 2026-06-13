package com.globaltalenthub.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "hak_search_results")
@Data
@NoArgsConstructor
public class SearchResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "search_query_id")
    private Long searchQueryId;

    @Column(name = "company_id")
    private Long companyId;

    @Column(nullable = false)
    private String url;

    private String title;
    private String snippet;
    private String domain;
    private Integer rank;

    @Column(nullable = false)
    private String provider;

    @Column(name = "source_tier")
    private Integer sourceTier;

    @Column(name = "tier_reason")
    private String tierReason;

    @Column(name = "document_type")
    private String documentType;

    @Column(name = "is_verification_source")
    private Boolean isVerificationSource;

    @Column(name = "extracted_data")
    private String extractedData;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
