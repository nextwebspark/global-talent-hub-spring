package com.globaltalenthub.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "hak_search_queries")
@Data
@NoArgsConstructor
public class SearchQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_key", nullable = false, unique = true)
    private String uniqueKey;

    @Column(nullable = false)
    private String query;

    @Column(name = "parsed_criteria")
    private String parsedCriteria;

    @Column(name = "result_count")
    private Integer resultCount;

    @Column(nullable = false)
    private String status;

    @Column(name = "selected_count")
    private Integer selectedCount;

    @Column(name = "clockwork_project_id")
    private String clockworkProjectId;

    @Column(name = "satellite_hierarchies", columnDefinition = "jsonb")
    @Type(JsonBinaryType.class)
    private Map<String, Object> satelliteHierarchies;

    @Column(name = "satellite_orders", columnDefinition = "jsonb")
    @Type(JsonBinaryType.class)
    private Map<String, Object> satelliteOrders;

    @Column(name = "table_config", columnDefinition = "jsonb")
    @Type(JsonBinaryType.class)
    private Map<String, Object> tableConfig;

    @Column(name = "map_positions", columnDefinition = "jsonb")
    @Type(JsonBinaryType.class)
    private Map<String, Object> mapPositions;

    @Column(name = "org_id")
    private String orgId;

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
        if (status == null) status = "draft";
        if (resultCount == null) resultCount = 0;
        if (selectedCount == null) selectedCount = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
