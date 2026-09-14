CREATE TABLE IF NOT EXISTS department_json_documents (
    document_key VARCHAR(96) NOT NULL PRIMARY KEY,
    document_schema_version INT NOT NULL,
    revision BIGINT NOT NULL,
    payload JSON NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_department_json_documents_revision CHECK (revision >= 0)
);
