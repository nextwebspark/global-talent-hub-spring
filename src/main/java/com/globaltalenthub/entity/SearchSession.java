package com.globaltalenthub.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "hak_search_sessions")
@Data
@NoArgsConstructor
public class SearchSession {

    @Id
    private String id;

    @Column(name = "raw_query", nullable = false)
    private String rawQuery;

    @Column(name = "pd_content")
    private String pdContent;

    @Column(name = "pd_confidential")
    private Boolean pdConfidential;

    @Column(name = "inferred_intent", columnDefinition = "jsonb")
    @Type(JsonBinaryType.class)
    private Map<String, Object> inferredIntent;

    @Column(nullable = false)
    private String status;

    @Column(name = "search_query_id")
    private Long searchQueryId;

    @Column(name = "refinement_history", columnDefinition = "jsonb")
    @Type(JsonBinaryType.class)
    private List<Object> refinementHistory;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (status == null) status = "pending";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
