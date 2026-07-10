package com.globaltalenthub.entity;

import io.hypersistence.utils.hibernate.type.array.StringArrayType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;

/**
 * Read-only JPA mapping over the {@code app_companies} master catalog
 * (docs/04_app_companies.sql). This is a shared master — no {@code org_id},
 * never written by the portal. Text-array columns use {@link StringArrayType}.
 *
 * <p>Style mirrors {@link Company}; only the fields the portal reads are mapped.
 */
@Entity
@Table(name = "app_companies")
@Data
@NoArgsConstructor
public class AppCompany {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // identity
    @Column(nullable = false)
    private String name;

    private String slogan;

    @Column(name = "linkedin_url")
    private String linkedinUrl;

    private String website;
    private String domain;
    private String logo;

    // classification
    @Column(name = "primary_industry")
    private String primaryIndustry;

    @Type(StringArrayType.class)
    @Column(name = "industry_tags", columnDefinition = "text[]")
    private String[] industryTags;

    @Type(StringArrayType.class)
    @Column(name = "sic_codes", columnDefinition = "text[]")
    private String[] sicCodes;

    @Type(StringArrayType.class)
    @Column(name = "sic_labels", columnDefinition = "text[]")
    private String[] sicLabels;

    @Type(StringArrayType.class)
    @Column(columnDefinition = "text[]")
    private String[] specialties;

    @Column(name = "org_type")
    private String orgType;

    private String ownership;

    @Column(name = "ipo_status")
    private String ipoStatus;

    @Column(name = "is_public")
    private Boolean isPublic;

    // size / money
    @Column(name = "revenue_usd")
    private Long revenueUsd;

    @Column(name = "revenue_range")
    private String revenueRange;

    @Column(name = "employee_count")
    private Integer employeeCount;

    @Column(name = "employee_range")
    private String employeeRange;

    // geo
    @Column(name = "hq_country")
    private String hqCountry;

    @Column(name = "hq_city")
    private String hqCity;

    @Type(StringArrayType.class)
    @Column(columnDefinition = "text[]")
    private String[] markets;

    // context / signals
    private String description;
    private Integer founded;
    private Integer followers;

    @Column(name = "gd_rating")
    private BigDecimal gdRating;

    @Column(name = "gd_reviews")
    private Integer gdReviews;

    @Column(name = "search_text")
    private String searchText;
}
