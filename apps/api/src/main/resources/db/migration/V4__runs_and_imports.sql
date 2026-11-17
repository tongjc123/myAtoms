-- Generation runs and their append-only event log. A run belongs to one project
-- and one anonymous owner; every visible "agent" step is a stored event, so a
-- reloaded page replays exactly what the server actually executed.
CREATE TABLE runs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purpose VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    engine VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    prompt TEXT NOT NULL,
    template_slug VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    theme VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    slide_count INT NULL,
    total_steps INT NOT NULL DEFAULT 0,
    cancel_requested TINYINT(1) NOT NULL DEFAULT 0,
    error_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_message VARCHAR(300) NULL,
    created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    INDEX ix_run_project (project_id, created_at),
    CONSTRAINT ck_run_status CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    CONSTRAINT fk_run_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_run_owner FOREIGN KEY (owner_session_id) REFERENCES anonymous_sessions(id)
) ENGINE=InnoDB;

CREATE TABLE run_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seq INT NOT NULL,
    type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT ux_run_event_seq UNIQUE (run_id, seq),
    CONSTRAINT fk_run_event_run FOREIGN KEY (run_id) REFERENCES runs(id)
) ENGINE=InnoDB;

-- Imported source decks (pptx/pdf). The original bytes are kept so a later
-- parser upgrade can re-read them without asking the visitor to upload again.
CREATE TABLE template_imports (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    owner_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(80) NOT NULL,
    filename VARCHAR(160) NOT NULL,
    content_type VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes INT NOT NULL,
    slide_count INT NOT NULL,
    parse_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outline MEDIUMTEXT NOT NULL,
    content MEDIUMBLOB NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX ix_import_owner (owner_session_id, created_at),
    CONSTRAINT ck_import_parse_state CHECK (parse_state IN ('PARSED','STORED_ONLY')),
    CONSTRAINT fk_import_owner FOREIGN KEY (owner_session_id) REFERENCES anonymous_sessions(id)
) ENGINE=InnoDB;
