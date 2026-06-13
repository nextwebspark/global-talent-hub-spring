package com.globaltalenthub.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "hak_organizations")
@Data
@NoArgsConstructor
public class Organization {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "team_size")
    private String teamSize;

    private String region;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "default_role", nullable = false)
    private String defaultRole;

    @Column(name = "require_2fa", nullable = false)
    private Boolean require2fa;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (defaultRole == null) defaultRole = "member";
        if (require2fa == null) require2fa = false;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
