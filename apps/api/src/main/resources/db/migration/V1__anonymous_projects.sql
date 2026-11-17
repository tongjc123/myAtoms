CREATE TABLE anonymous_sessions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    secret_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    INDEX ix_session_expiry (expires_at)
) ENGINE=InnoDB;

CREATE TABLE projects (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    owner_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(80) NOT NULL,
    type VARCHAR(16) CHARACTER SET ascii NOT NULL,
    prompt TEXT NOT NULL,
    mode VARCHAR(8) CHARACTER SET ascii NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    CONSTRAINT fk_project_owner FOREIGN KEY (owner_session_id) REFERENCES anonymous_sessions(id),
    CONSTRAINT ck_project_type CHECK (type IN ('WEB','GAME','SLIDES','VIDEO')),
    CONSTRAINT ck_project_mode CHECK (mode IN ('BUILD','PLAN')),
    INDEX ix_project_owner (owner_session_id, deleted_at, updated_at, id)
) ENGINE=InnoDB;

CREATE TABLE idempotency_requests (
    owner_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_status INT NOT NULL,
    response_body MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_session_id, request_key),
    CONSTRAINT fk_receipt_owner FOREIGN KEY (owner_session_id) REFERENCES anonymous_sessions(id)
) ENGINE=InnoDB;
