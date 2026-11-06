#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
set -a
if [ -e .env ]; then . ./.env; fi
set +a
python3 - <<'PY'
import base64
import json
import os
import urllib.error
import urllib.request

if os.environ.get('SMOKE_ENV_FILE'):
    with open(os.environ['SMOKE_ENV_FILE']) as file:
        os.environ.update(json.load(file))

base = os.environ.get('BASE_URL', 'http://127.0.0.1:' + os.environ.get('APP_PORT', '8080'))
checks = 0

def request(method, path, role=None, body=None, expected=200):
    global checks
    headers = {'Content-Type': 'application/json'}
    if role:
        password = os.environ[role.upper() + '_PASSWORD']
        token = base64.b64encode((role + ':' + password).encode()).decode()
        headers['Authorization'] = 'Basic ' + token
    req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body is not None else None,
                                 headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status = response.status
        content_type = response.headers.get('Content-Type', '')
        raw = response.read()
    if status != expected:
        raise SystemExit(f'{method} {path}: expected {expected}, received {status}')
    checks += 1
    return json.loads(raw) if raw else None, content_type

request('GET', '/health')
request('GET', '/api/results', expected=401)
if not os.environ.get('SMOKE_ENV_FILE'):
    seed, _ = request('GET', '/api/results/11111111-1111-4111-8111-111111111111', 'reader')
    assert seed['patientId'] == 'SYN-DEMO-001'
result, _ = request('POST', '/api/results', 'technician', {
    'patientId': 'SYN-DEMO-002', 'specimenId': 'LAB-DEMO-002', 'test': 'POTASSIUM',
    'value': 4.1, 'observedAt': '2026-01-15T10:30:00Z'}, 201)
path = '/api/results/' + result['id']
read, _ = request('GET', path, 'reader')
assert read['value'] == 4.1 and read['revision'] == 1
correction = {'expectedRevision': 1, 'value': 4.3, 'reason': 'Rechecked synthetic instrument reading'}
request('POST', path + '/corrections', 'reader', correction, 403)
amended, _ = request('POST', path + '/corrections', 'technician', correction)
assert amended['value'] == 4.3 and amended['revision'] == 2
request('POST', path + '/corrections', 'technician', correction, 409)
history, _ = request('GET', path + '/history', 'reader')
assert len(history) == 2 and history[0]['snapshot']['value'] == 4.1 and history[1]['actor'] == 'technician'
fhir, content_type = request('GET', path + '/fhir', 'reader')
assert content_type.startswith('application/fhir+json')
assert fhir['resourceType'] == 'Observation' and fhir['status'] == 'amended'
assert fhir['code']['coding'][0]['code'] == '2823-3' and fhir['valueQuantity']['value'] == 4.3
assert fhir['meta']['versionId'] == '2'
request('POST', path + '/corrections', 'technician', dict(correction, expectedRevision=2, value=-1), 400)
print(f'{checks} HTTP checks passed: create, read, roles, correction, conflict, history, FHIR and validation.')
PY
