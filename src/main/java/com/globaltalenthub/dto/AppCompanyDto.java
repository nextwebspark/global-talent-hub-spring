package com.globaltalenthub.dto;

import com.globaltalenthub.entity.AppCompany;

import java.math.BigDecimal;
import java.util.List;

/**
 * Flat camelCase view of an {@code app_companies} master row the React client
 * consumes. Text-array columns become {@code List<String>}.
 */
public record AppCompanyDto(
    Long id,
    String name,
    String slogan,
    String linkedinUrl,
    String website,
    String domain,
    String logo,
    String primaryIndustry,
    List<String> industryTags,
    List<String> sicCodes,
    List<String> sicLabels,
    List<String> specialties,
    String orgType,
    String ownership,
    String ipoStatus,
    Boolean isPublic,
    Long revenueUsd,
    String revenueRange,
    Integer employeeCount,
    String employeeRange,
    String hqCountry,
    String hqCity,
    List<String> markets,
    String description,
    Integer founded,
    Integer followers,
    BigDecimal gdRating,
    Integer gdReviews,
    /**
     * Membership of this catalog company in a specific project: the {@code app_project_companies}
     * status (in_universe / shortlisted / declined / untriaged) when the search was project-scoped,
     * else {@code null} (not in the project, or the search wasn't project-aware).
     */
    String projectStatus
) {
    public static AppCompanyDto from(AppCompany c) {
        return from(c, null);
    }

    public static AppCompanyDto from(AppCompany c, String projectStatus) {
        return new AppCompanyDto(
            c.getId(),
            c.getName(),
            c.getSlogan(),
            c.getLinkedinUrl(),
            c.getWebsite(),
            c.getDomain(),
            c.getLogo(),
            c.getPrimaryIndustry(),
            arr(c.getIndustryTags()),
            arr(c.getSicCodes()),
            arr(c.getSicLabels()),
            arr(c.getSpecialties()),
            c.getOrgType(),
            c.getOwnership(),
            c.getIpoStatus(),
            c.getIsPublic(),
            c.getRevenueUsd(),
            c.getRevenueRange(),
            c.getEmployeeCount(),
            c.getEmployeeRange(),
            c.getHqCountry(),
            c.getHqCity(),
            arr(c.getMarkets()),
            c.getDescription(),
            c.getFounded(),
            c.getFollowers(),
            c.getGdRating(),
            c.getGdReviews(),
            projectStatus
        );
    }

    private static List<String> arr(String[] a) {
        return a == null ? List.of() : List.of(a);
    }
}
