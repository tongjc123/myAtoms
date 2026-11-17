ALTER TABLE idempotency_requests
    ADD COLUMN project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD INDEX ix_receipt_project (owner_session_id, project_id);

UPDATE idempotency_requests
SET project_id = CASE
    WHEN JSON_VALID(response_body) THEN JSON_UNQUOTE(JSON_EXTRACT(response_body, '$.project.id'))
    ELSE NULL
END
WHERE response_status IN (200, 201);
