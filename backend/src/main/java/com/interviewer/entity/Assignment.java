package com.interviewer.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "assignments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cohort_id", nullable = false)
    private Cohort cohort;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "track_id")
    private CompanyTrack track;

    @Column(nullable = false, length = 200)
    private String title;

    private LocalDateTime dueAt;

    @Column(nullable = false)
    @Builder.Default
    private Integer maxAttempts = 1;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
