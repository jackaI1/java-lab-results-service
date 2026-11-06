INSERT INTO lab_result (id, patient_id, specimen_id, test, value, observed_at, status, revision, updated_at)
VALUES ('11111111-1111-4111-8111-111111111111', 'SYN-DEMO-001', 'LAB-DEMO-001', 'GLUCOSE', 5.4,
        '2026-01-15 10:30:00', 'FINAL', 1, '2026-01-15 11:00:00');
INSERT INTO result_audit (result_id, revision, actor, reason, changed_at, snapshot)
SELECT id, revision, 'demo-seed', 'Synthetic example loaded by demo profile', updated_at,
       JSON_OBJECT('id', id, 'patientId', patient_id, 'specimenId', specimen_id, 'test', test,
          'value', value, 'unit', 'mmol/L', 'observedAt', '2026-01-15T10:30:00Z', 'status', status,
          'revision', revision, 'updatedAt', '2026-01-15T11:00:00Z')
FROM lab_result WHERE id = '11111111-1111-4111-8111-111111111111';
