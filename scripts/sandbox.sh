#!/usr/bin/env bash
# Run only on a disposable Linux Docker host (CI is the usual entry point).
set -euo pipefail
cd "$(dirname "$0")/.."
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1 AWS_EC2_METADATA_DISABLED=true
export TF_IN_AUTOMATION=true
apis=(docker compose -p lab-aws-apis -f compose.sandbox.yaml)
runtime=(docker compose -p lab-aws-runtime -f .sandbox/compose.json)
cleanup() {
  status=$?
  trap - EXIT
  if [[ -f .sandbox/compose.json ]]; then "${runtime[@]}" down -v --remove-orphans >/dev/null 2>&1 || status=1; fi
  if [[ -f infra/terraform.tfstate ]]; then terraform -chdir=infra destroy -auto-approve -input=false >/dev/null 2>&1 || status=1; fi
  "${apis[@]}" down -v --remove-orphans >/dev/null 2>&1 || status=1
  # Assert this script's named containers are gone, without touching others.
  for project in lab-aws-runtime lab-aws-apis; do
    [[ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$project")" ]] || status=1
  done
  rm -rf .sandbox infra/terraform.tfstate* infra/sandbox.auto.tfvars.json
  echo "Sandbox cleanup complete (exit $status)."
  exit "$status"
}
trap cleanup EXIT
mkdir -p .sandbox
chmod 700 .sandbox
"${apis[@]}" up -d --wait --wait-timeout 120
python3 - <<'PY'
import json, secrets, time, urllib.request
from pathlib import Path
import os
os.umask(0o077)
for port in (4566, 5000):
    for attempt in range(60):
        try:
            urllib.request.urlopen(f'http://localhost:{port}/', timeout=2)
            break
        except urllib.error.HTTPError:
            break  # An AWS root request may return 404, but the server is listening.
        except OSError:
            time.sleep(1)
    else:
        raise SystemExit(f'API port {port} never became available')
Path('infra/sandbox.auto.tfvars.json').write_text(json.dumps({
    'sandbox': True, 'image': os.environ.get('LAB_IMAGE', 'lab-results:ci'),
    'db_password': secrets.token_hex(24), 'reader_password': secrets.token_hex(24),
    'technician_password': secrets.token_hex(24)}))
PY
terraform -chdir=infra init -input=false
terraform -chdir=infra fmt -check
terraform -chdir=infra validate
terraform -chdir=infra apply -auto-approve -input=false
python3 scripts/sandbox-render.py
"${runtime[@]}" up -d --wait --wait-timeout 180
BASE_URL=http://127.0.0.1:18080 SMOKE_ENV_FILE=.sandbox/smoke-env.json ./scripts/demo.sh
# A process replacement must keep both values in the database, not only in memory.
"${runtime[@]}" restart app
"${runtime[@]}" up -d --wait --wait-timeout 120
BASE_URL=http://127.0.0.1:18080 SMOKE_ENV_FILE=.sandbox/smoke-env.json python3 scripts/sandbox-persistence.py
