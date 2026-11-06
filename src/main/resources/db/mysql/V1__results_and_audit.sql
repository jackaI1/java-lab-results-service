CREATE TABLE lab_result (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    patient_id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        CHECK (LEFT(patient_id, 4) = 'SYN-' AND CHAR_LENGTH(patient_id) BETWEEN 5 AND 36
               AND NOT REGEXP_LIKE(SUBSTRING(patient_id, 5), '[^A-Z0-9-]', 'c')),
    specimen_id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        CHECK (LEFT(specimen_id, 4) = 'LAB-' AND CHAR_LENGTH(specimen_id) BETWEEN 5 AND 36
               AND NOT REGEXP_LIKE(SUBSTRING(specimen_id, 5), '[^A-Z0-9-]', 'c')),
    test VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        CHECK (CAST(test AS BINARY) IN ('GLUCOSE', 'POTASSIUM', 'HEMOGLOBIN')),
    value DECIMAL(11,4) NOT NULL CHECK (value >= 0 AND value <= 1000000),
    observed_at DATETIME(6) NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK (CAST(status AS BINARY) IN ('FINAL', 'AMENDED')),
    revision INTEGER NOT NULL CHECK (revision > 0),
    updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;
CREATE INDEX results_patient_observed ON lab_result (patient_id, observed_at DESC, id);
CREATE INDEX results_observed ON lab_result (observed_at DESC, id);
CREATE TABLE result_audit (
    result_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    actor VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    snapshot JSON NOT NULL,
    PRIMARY KEY (result_id, revision),
    FOREIGN KEY (result_id) REFERENCES lab_result(id)
) ENGINE=InnoDB;
CREATE TRIGGER result_audit_no_update BEFORE UPDATE ON result_audit
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'result_audit is append-only';
CREATE TRIGGER result_audit_no_delete BEFORE DELETE ON result_audit
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'result_audit is append-only';
