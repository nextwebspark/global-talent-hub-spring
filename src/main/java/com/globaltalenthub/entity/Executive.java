package com.globaltalenthub.entity;

import io.hypersistence.utils.hibernate.type.array.StringArrayType;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Map;

@Entity
@Table(name = "hak_executives")
@Data
@NoArgsConstructor
public class Executive {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String title;

    private String email;
    private String phone;
    private String linkedin;

    @Column(name = "profile_url")
    private String profileUrl;

    @Column(name = "image_url")
    private String imageUrl;

    private String notes;

    @Column(name = "remuneration_notes")
    private String remunerationNotes;

    private String availability;
    private String level;

    @Column(name = "source_text")
    private String sourceText;

    private String source;
    private Integer confidence;

    @Column(name = "enrichment_source")
    private String enrichmentSource;

    @Column(name = "enrichment_confidence")
    private Integer enrichmentConfidence;

    @Column(name = "enrichment_timestamp")
    private LocalDateTime enrichmentTimestamp;

    @Column(name = "clockwork_id")
    private String clockworkId;

    @Column(name = "clockwork_project_id")
    private String clockworkProjectId;

    private String gender;

    @Column(name = "gender_confidence")
    private Integer genderConfidence;

    private String ethnicity;

    @Column(name = "ethnicity_confidence")
    private Integer ethnicityConfidence;

    @Column(name = "executive_confidence")
    private String executiveConfidence;

    @Column(name = "executive_confidence_reason")
    private String executiveConfidenceReason;

    @Column(name = "custom_fields", columnDefinition = "jsonb")
    @Type(JsonBinaryType.class)
    private Map<String, Object> customFields;

    @Column(name = "manually_edited_fields", columnDefinition = "text[]")
    @Type(StringArrayType.class)
    private String[] manuallyEditedFields;

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
