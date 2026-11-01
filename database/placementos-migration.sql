-- PlacementOS migration for an existing AI-Interviewer MySQL/TiDB database.
-- Run once. Hibernate's ddl-auto=update also applies these changes on boot;
-- this file exists for DBAs who manage schema out of band.
--
-- H2 gets the same schema from Hibernate in the "local" profile and tests.

USE ai_interviewer;

-- ---------------------------------------------------------------------------
-- PlacementOS (multi-tenant)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS organizations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    slug VARCHAR(80) NOT NULL UNIQUE,
    plan ENUM('PILOT', 'STARTER', 'GROWTH') NOT NULL DEFAULT 'PILOT',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_slug (slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS memberships (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role ENUM('TPO', 'STUDENT', 'ADMIN') NOT NULL DEFAULT 'STUDENT',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_membership_org_user UNIQUE (organization_id, user_id),
    FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_membership_org (organization_id),
    INDEX idx_membership_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cohorts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT NOT NULL,
    name VARCHAR(150) NOT NULL,
    department VARCHAR(120),
    graduation_year INT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
    INDEX idx_cohort_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS enrollments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cohort_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_enrollment_cohort_student UNIQUE (cohort_id, student_id),
    FOREIGN KEY (cohort_id) REFERENCES cohorts(id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_enrollment_cohort (cohort_id),
    INDEX idx_enrollment_student (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS company_tracks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(80),
    config_json TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
    INDEX idx_track_org (organization_id),
    INDEX idx_track_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS assignments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cohort_id BIGINT NOT NULL,
    track_id BIGINT,
    title VARCHAR(200) NOT NULL,
    due_at TIMESTAMP NULL,
    max_attempts INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (cohort_id) REFERENCES cohorts(id) ON DELETE CASCADE,
    FOREIGN KEY (track_id) REFERENCES company_tracks(id) ON DELETE SET NULL,
    INDEX idx_assignment_cohort (cohort_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS assignment_attempts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    assignment_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    interview_id BIGINT,
    status ENUM('IN_PROGRESS', 'COMPLETED', 'EXPIRED') NOT NULL DEFAULT 'IN_PROGRESS',
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (interview_id) REFERENCES interviews(id) ON DELETE SET NULL,
    INDEX idx_attempt_assignment (assignment_id),
    INDEX idx_attempt_student (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Tenant columns (nullable for legacy single-user rows)
ALTER TABLE users
    ADD COLUMN consent_recording BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN consent_proctoring BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN data_retention_days INT NOT NULL DEFAULT 30;

ALTER TABLE interviews ADD COLUMN organization_id BIGINT NULL;
ALTER TABLE reports ADD COLUMN organization_id BIGINT NULL;
ALTER TABLE resumes ADD COLUMN organization_id BIGINT NULL;
ALTER TABLE job_descriptions ADD COLUMN organization_id BIGINT NULL;

CREATE INDEX idx_interview_org ON interviews (organization_id);
CREATE INDEX idx_report_org ON reports (organization_id);
CREATE INDEX idx_resume_org ON resumes (organization_id);
CREATE INDEX idx_jd_org ON job_descriptions (organization_id);

-- The five company tracks are seeded by CompanyTrackService on application
-- startup from backend/src/main/resources/company-tracks/*.json, so a DB-only
-- migration does not need to duplicate that JSON.
