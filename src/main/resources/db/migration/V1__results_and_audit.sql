CREATE TABLE lab_result (
    id UUID PRIMARY KEY,
    patient_id VARCHAR(40) NOT NULL CHECK (patient_id ~ '^SYN-[A-Z0-9-]{1,32}$'),
    specimen_id VARCHAR(40) NOT NULL CHECK (specimen_id ~ '^LAB-[A-Z0-9-]{1,32}$'),
    test VARCHAR(30) NOT NULL CHECK (test IN ('GLUCOSE', 'POTASSIUM', 'HEMOGLOBIN')),
    value NUMERIC(11,4) NOT NULL CHECK (value >= 0 AND value <= 1000000),
    observed_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('FINAL', 'AMENDED')),
    revision INTEGER NOT NULL CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX results_patient_observed ON lab_result (patient_id, observed_at DESC, id);
CREATE INDEX results_observed ON lab_result (observed_at DESC, id);
CREATE TABLE result_audit (
    result_id UUID NOT NULL REFERENCES lab_result(id),
    revision INTEGER NOT NULL CHECK (revision > 0),
    actor VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    snapshot JSONB NOT NULL,
    PRIMARY KEY (result_id, revision)
);
CREATE FUNCTION protect_result_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'result_audit is append-only';
END;
$$;
CREATE TRIGGER result_audit_append_only
BEFORE UPDATE OR DELETE ON result_audit
FOR EACH ROW EXECUTE FUNCTION protect_result_audit();
