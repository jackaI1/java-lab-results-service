CREATE TABLE lab_result (
    id UNIQUEIDENTIFIER PRIMARY KEY,
    patient_id VARCHAR(40) COLLATE Latin1_General_100_BIN2 NOT NULL CHECK (LEFT(patient_id, 4) = 'SYN-' AND DATALENGTH(patient_id) BETWEEN 5 AND 36 AND SUBSTRING(patient_id, 5, 32) NOT LIKE '%[^A-Z0-9-]%'),
    specimen_id VARCHAR(40) COLLATE Latin1_General_100_BIN2 NOT NULL CHECK (LEFT(specimen_id, 4) = 'LAB-' AND DATALENGTH(specimen_id) BETWEEN 5 AND 36 AND SUBSTRING(specimen_id, 5, 32) NOT LIKE '%[^A-Z0-9-]%'),
    test VARCHAR(30) COLLATE Latin1_General_100_BIN2 NOT NULL CHECK (test IN ('GLUCOSE', 'POTASSIUM', 'HEMOGLOBIN') AND DATALENGTH(test) = LEN(test)),
    value DECIMAL(11,4) NOT NULL CHECK (value >= 0 AND value <= 1000000),
    observed_at DATETIME2(6) NOT NULL,
    status VARCHAR(20) COLLATE Latin1_General_100_BIN2 NOT NULL CHECK (status IN ('FINAL', 'AMENDED') AND DATALENGTH(status) = LEN(status)),
    revision INT NOT NULL CHECK (revision > 0),
    updated_at DATETIME2(6) NOT NULL
);
CREATE INDEX results_patient_observed ON lab_result (patient_id, observed_at DESC, id);
CREATE INDEX results_observed ON lab_result (observed_at DESC, id);
CREATE TABLE result_audit (
    result_id UNIQUEIDENTIFIER NOT NULL REFERENCES lab_result(id),
    revision INT NOT NULL CHECK (revision > 0),
    actor VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    changed_at DATETIME2(6) NOT NULL,
    snapshot NVARCHAR(MAX) NOT NULL CHECK (ISJSON(snapshot) = 1),
    PRIMARY KEY (result_id, revision)
);
GO
CREATE TRIGGER result_audit_append_only ON result_audit INSTEAD OF UPDATE, DELETE AS
BEGIN
    THROW 50001, 'result_audit is append-only', 1;
END;
GO
CREATE PROCEDURE revise_result @id UNIQUEIDENTIFIER, @expected_revision INT, @value DECIMAL(11,4), @updated_at DATETIME2(6) AS
BEGIN
    SET NOCOUNT ON;
    UPDATE lab_result SET value = @value, status = 'AMENDED', revision = revision + 1, updated_at = @updated_at
    WHERE id = @id AND revision = @expected_revision;
    SELECT @@ROWCOUNT AS changed;
END;
GO
