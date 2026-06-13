package com.globaltalenthub.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "hak_remuneration")
@Data
@NoArgsConstructor
public class Remuneration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "executive_id", nullable = false)
    private Long executiveId;

    @Column(name = "base_salary", precision = 15, scale = 2)
    private BigDecimal baseSalary;

    @Column(name = "housing_allowance", precision = 15, scale = 2)
    private BigDecimal housingAllowance;

    @Column(name = "transport_allowance", precision = 15, scale = 2)
    private BigDecimal transportAllowance;

    @Column(name = "schooling_allowance", precision = 15, scale = 2)
    private BigDecimal schoolingAllowance;

    @Column(name = "total_allowances", precision = 15, scale = 2)
    private BigDecimal totalAllowances;

    @Column(precision = 15, scale = 2)
    private BigDecimal bonus;

    @Column(name = "long_term_incentives", precision = 15, scale = 2)
    private BigDecimal longTermIncentives;

    private String currency;
    private String year;
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (currency == null) currency = "USD";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
