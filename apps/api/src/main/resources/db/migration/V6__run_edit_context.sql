ALTER TABLE runs
    ADD COLUMN instruction TEXT NULL,
    ADD COLUMN base_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN request_payload TEXT NULL,
    ADD CONSTRAINT ux_run_request UNIQUE (project_id, request_key),
    ADD CONSTRAINT fk_run_base FOREIGN KEY (base_version_id) REFERENCES project_versions(id);
