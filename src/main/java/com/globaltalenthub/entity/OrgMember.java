package com.globaltalenthub.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "hak_org_members")
@Data
@NoArgsConstructor
public class OrgMember {

    @Id
    private String id;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    private String email;

    @Column(nullable = false)
    private String role;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (role == null) role = "member";
    }
}
