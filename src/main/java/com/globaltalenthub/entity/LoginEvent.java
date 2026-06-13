package com.globaltalenthub.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "hak_login_events")
@Data
@NoArgsConstructor
public class LoginEvent {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "org_id")
    private String orgId;

    @Column(nullable = false)
    private LocalDateTime at;

    private String ip;

    @Column(name = "user_agent")
    private String userAgent;

    @PrePersist
    protected void onCreate() {
        if (at == null) at = LocalDateTime.now();
    }
}
