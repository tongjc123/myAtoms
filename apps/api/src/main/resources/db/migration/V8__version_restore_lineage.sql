ALTER TABLE project_versions
    ADD COLUMN restored_from_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT fk_project_version_restored_from
        FOREIGN KEY (restored_from_version_id) REFERENCES project_versions(id);
