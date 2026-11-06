INSERT INTO lab_result (id, patient_id, specimen_id, test, value, observed_at, status, revision, updated_at)
VALUES ('11111111-1111-4111-8111-111111111111', 'SYN-DEMO-001', 'LAB-DEMO-001', 'GLUCOSE', 5.4, '2026-01-15T10:30:00', 'FINAL', 1, '2026-01-15T11:00:00');
INSERT INTO result_audit (result_id, revision, actor, reason, changed_at, snapshot)
VALUES ('11111111-1111-4111-8111-111111111111', 1, 'demo-seed', 'Synthetic example loaded by demo profile', '2026-01-15T11:00:00', '{"id":"11111111-1111-4111-8111-111111111111","patientId":"SYN-DEMO-001","specimenId":"LAB-DEMO-001","test":"GLUCOSE","value":5.4,"unit":"mmol/L","observedAt":"2026-01-15T10:30:00Z","status":"FINAL","revision":1,"updatedAt":"2026-01-15T11:00:00Z"}');
