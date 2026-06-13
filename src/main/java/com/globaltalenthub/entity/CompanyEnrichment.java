package com.globaltalenthub.entity;

import io.hypersistence.utils.hibernate.type.array.StringArrayType;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Plain table — no hak_ prefix. FK company_pk → hak_companies.id.
 */
@Entity
@Table(name = "company_enrichment")
@Data
@NoArgsConstructor
public class CompanyEnrichment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // company_pk is nullable on the live table (rows may predate the FK backfill).
    @Column(name = "company_pk")
    private Long companyPk;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    // company_name/slug/country are carried directly on company_enrichment
    // (text, default '') — the seed query reads them without a join.
    @Column(name = "company_name")
    private String companyName;

    @Column(name = "slug")
    private String slug;

    @Column(name = "country")
    private String country;

    @Column(name = "primary_sector", nullable = false)
    private String primarySector;

    @Column(name = "sector_tags", columnDefinition = "text[]")
    @Type(StringArrayType.class)
    private String[] sectorTags;

    @Column(name = "adjacent_sectors", columnDefinition = "text[]")
    @Type(StringArrayType.class)
    private String[] adjacentSectors;

    @Column(name = "sub_tags", columnDefinition = "text[]")
    @Type(StringArrayType.class)
    private String[] subTags;

    @Column(columnDefinition = "text[]")
    @Type(StringArrayType.class)
    private String[] keywords;

    @Column(name = "proposed_tags", columnDefinition = "text[]")
    @Type(StringArrayType.class)
    private String[] proposedTags;

    private String tagline;

    @Column(name = "business_description")
    private String businessDescription;

    @Column(name = "employee_band")
    private String employeeBand;

    @Column(name = "employee_count_estimate")
    private Integer employeeCountEstimate;

    @Column(name = "revenue_band")
    private String revenueBand;

    @Column(name = "revenue_estimate_usd")
    private Long revenueEstimateUsd;

    @Column(name = "is_listed")
    private Boolean isListed;

    @Column(name = "hq_city")
    private String hqCity;

    @Column(precision = 3, scale = 2, nullable = false)
    private BigDecimal confidence;

    @Column(columnDefinition = "jsonb", nullable = false)
    @Type(JsonBinaryType.class)
    private List<Object> sources;

    @Column(nullable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Column(name = "enriched_at", nullable = false)
    private OffsetDateTime enrichedAt;

    @Column(name = "raw_response", columnDefinition = "jsonb")
    @Type(JsonBinaryType.class)
    private Map<String, Object> rawResponse;

    private String website;
    private String phone;
    private String email;
    private String address;

    @Column(name = "sector_mix", columnDefinition = "jsonb", nullable = false)
    @Type(JsonBinaryType.class)
    private List<Object> sectorMix;

    @PrePersist
    protected void onCreate() {
        if (enrichedAt == null) enrichedAt = OffsetDateTime.now();
    }
}
