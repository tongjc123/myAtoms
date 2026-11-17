-- Template catalogue and immutable file snapshots. A template version never
-- changes in place: editing a template bundle produces a new version row and
-- existing projects keep the copy they were cloned from.
CREATE TABLE templates (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    slug VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(80) NOT NULL,
    type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    category VARCHAR(32) NOT NULL,
    summary VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    author VARCHAR(80) NOT NULL,
    source_url VARCHAR(300) NULL,
    license_note VARCHAR(120) NULL,
    cover_path VARCHAR(300) CHARACTER SET ascii COLLATE ascii_bin NULL,
    sort_order INT NOT NULL DEFAULT 0,
    current_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT ux_template_slug UNIQUE (slug),
    CONSTRAINT ck_template_type CHECK (type IN ('WEB','GAME','SLIDES','VIDEO'))
) ENGINE=InnoDB;

CREATE TABLE template_versions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    template_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_no INT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entry_path VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_count INT NOT NULL,
    total_bytes BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT ux_template_version_no UNIQUE (template_id, version_no),
    CONSTRAINT ux_template_version_hash UNIQUE (template_id, content_hash),
    CONSTRAINT fk_template_version FOREIGN KEY (template_id) REFERENCES templates(id)
) ENGINE=InnoDB;

CREATE TABLE template_files (
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    path VARCHAR(300) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes INT NOT NULL,
    content MEDIUMBLOB NOT NULL,
    PRIMARY KEY (version_id, path),
    CONSTRAINT fk_template_file_version FOREIGN KEY (version_id) REFERENCES template_versions(id)
) ENGINE=InnoDB;

CREATE TABLE project_versions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_no INT NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parent_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    template_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    summary VARCHAR(200) NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entry_path VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT ux_project_version_no UNIQUE (project_id, version_no),
    CONSTRAINT ck_project_version_kind CHECK (kind IN ('TEMPLATE','GENERATED','EDIT','RESTORE')),
    CONSTRAINT fk_project_version_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_project_version_template FOREIGN KEY (template_version_id) REFERENCES template_versions(id)
) ENGINE=InnoDB;

CREATE TABLE project_files (
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    path VARCHAR(300) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes INT NOT NULL,
    content MEDIUMBLOB NOT NULL,
    PRIMARY KEY (version_id, path),
    CONSTRAINT fk_project_file_version FOREIGN KEY (version_id) REFERENCES project_versions(id)
) ENGINE=InnoDB;

ALTER TABLE projects
    ADD COLUMN current_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN source_template_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT fk_project_current_version FOREIGN KEY (current_version_id) REFERENCES project_versions(id),
    ADD CONSTRAINT fk_project_source_template FOREIGN KEY (source_template_version_id) REFERENCES template_versions(id);

-- Preview access is granted through an opaque, short-lived token instead of the
-- owner cookie: a sandboxed iframe has an opaque origin and cannot be relied on
-- to carry the anonymous session.
CREATE TABLE preview_sessions (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    INDEX ix_preview_expiry (expires_at),
    CONSTRAINT fk_preview_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_preview_version FOREIGN KEY (version_id) REFERENCES project_versions(id),
    CONSTRAINT fk_preview_owner FOREIGN KEY (owner_session_id) REFERENCES anonymous_sessions(id)
) ENGINE=InnoDB;
