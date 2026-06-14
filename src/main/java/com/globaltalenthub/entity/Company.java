package com.globaltalenthub.entity;

import io.hypersistence.utils.hibernate.type.array.StringArrayType;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "hak_companies")
@Data
@NoArgsConstructor
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String sector;

    @Column(name = "sector_category")
    private String sectorCategory;

    @Column(name = "business_type")
    private String businessType;

    @Column(name = "ownership_type")
    private String ownershipType;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "is_operating_company")
    private Boolean isOperatingCompany;

    private String region;
    private String country;

    @Column(name = "street_address")
    private String streetAddress;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "location_precision")
    private String locationPrecision;

    @Column(precision = 15, scale = 2)
    private BigDecimal revenue;

    @Column(name = "revenue_source")
    private String revenueSource;

    @Column(name = "revenue_source_url")
    private String revenueSourceUrl;

    @Column(name = "revenue_confidence")
    private Integer revenueConfidence;

    @Column(name = "revenue_currency")
    private String revenueCurrency;

    @Column(name = "revenue_fiscal_year")
    private Integer revenueFiscalYear;

    @Column(name = "revenue_converted_from_currency")
    private String revenueConvertedFromCurrency;

    @Column(name = "revenue_fx_rate", precision = 10, scale = 6)
    private BigDecimal revenueFxRate;

    @Column(name = "revenue_fx_policy")
    private String revenueFxPolicy;

    @Column(name = "revenue_last_updated")
    private LocalDateTime revenueLastUpdated;

    private Integer employees;

    @Column(name = "employees_source")
    private String employeesSource;

    @Column(name = "employees_source_url")
    private String employeesSourceUrl;

    @Column(name = "employees_confidence")
    private Integer employeesConfidence;

    @Column(name = "employees_last_updated")
    private LocalDateTime employeesLastUpdated;

    @Column(name = "geographic_footprint")
    private Integer geographicFootprint;

    @Column(name = "customer_model")
    private String customerModel;

    @Column(name = "core_activity")
    private String coreActivity;

    @Column(name = "operating_model")
    private String operatingModel;

    @Column(name = "revenue_drivers")
    private String revenueDrivers;

    private String summary;
    private String website;

    @Column(name = "last_verified_year")
    private Integer lastVerifiedYear;

    private Integer confidence;
    private String status;
    private String color;

    @Column(name = "relevance_reason")
    private String relevanceReason;

    @Column(name = "manually_edited_fields", columnDefinition = "text[]")
    @Type(StringArrayType.class)
    private String[] manuallyEditedFields;

    @Column(name = "data_provenance", columnDefinition = "jsonb")
    @Type(JsonBinaryType.class)
    private Map<String, Object> dataProvenance;

    @Column(name = "search_query_id")
    private Long searchQueryId;

    @Column(name = "relevance_type")
    private String relevanceType;

    @Column(name = "relevance_rationale")
    private String relevanceRationale;

    @Column(name = "confidence_score")
    private Integer confidenceScore;

    @Column(name = "sub_sector")
    private String subSector;

    @Column(name = "company_size")
    private String companySize;

    @Column(name = "revenue_range")
    private String revenueRange;

    @Column(name = "is_user_accepted")
    private Boolean isUserAccepted;

    @Column(name = "is_user_rejected")
    private Boolean isUserRejected;

    private String geography;

    @Column(name = "search_session_id")
    private String searchSessionId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
