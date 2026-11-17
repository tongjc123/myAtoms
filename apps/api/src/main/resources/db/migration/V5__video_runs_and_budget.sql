-- Video runs remember the exact model and specification that was requested, and the
-- provider task id so a stopped run can cancel the remote job. Budget reservations are
-- stored per run: a reservation is taken before a paid call and released or settled after.
ALTER TABLE runs
    ADD COLUMN model_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER import_id,
    ADD COLUMN spec_json VARCHAR(300) NULL AFTER model_key,
    ADD COLUMN provider_task_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER spec_json;

CREATE TABLE provider_spend (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    model_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reserved_cny DECIMAL(10,2) NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT ux_spend_run UNIQUE (run_id),
    CONSTRAINT ck_spend_state CHECK (state IN ('RESERVED','SETTLED','RELEASED')),
    CONSTRAINT fk_spend_run FOREIGN KEY (run_id) REFERENCES runs(id)
) ENGINE=InnoDB;
