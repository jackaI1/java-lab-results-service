#!/usr/bin/env python3
"""Execute the deployed API model with real containers, without paid emulators.

This is an explicit runtime adapter, not an ECS/RDS/ALB implementation. It reads
back the task, service, database, listener and secret; changing their wiring must
break this check rather than accidentally exercising an unrelated Compose demo.
"""
import json
import os
from pathlib import Path
import boto3

root = Path(__file__).resolve().parents[1]
out = root / '.sandbox'
out.mkdir(mode=0o700, exist_ok=True)
os.umask(0o077)

def client(service, port=5000):
    return boto3.client(service, endpoint_url=f'http://localhost:{port}', region_name='us-east-1')

ecs, rds, elb = client('ecs'), client('rds'), client('elbv2')
service = ecs.describe_services(cluster='lab-results', services=['lab-results'])['services'][0]
assert service['desiredCount'] == 1 and service['launchType'] == 'FARGATE'
task = ecs.describe_task_definition(taskDefinition=service['taskDefinition'])['taskDefinition']
assert task['networkMode'] == 'awsvpc'
app, = task['containerDefinitions']
assert app['name'] == 'app' and app['essential']
assert app['portMappings'][0]['containerPort'] == 8080
assert service['loadBalancers'][0]['containerName'] == app['name']
assert service['loadBalancers'][0]['containerPort'] == 8080

target_arn = service['loadBalancers'][0]['targetGroupArn']
target, = elb.describe_target_groups(TargetGroupArns=[target_arn])['TargetGroups']
assert target['Port'] == 8080 and target['Protocol'] == 'HTTP'
assert target['HealthCheckPath'] == '/health' and target['TargetType'] == 'ip'
alb, = elb.describe_load_balancers(Names=['lab-results'])['LoadBalancers']
listener, = elb.describe_listeners(LoadBalancerArn=alb['LoadBalancerArn'])['Listeners']
assert listener['Protocol'] == 'HTTP' and listener['Port'] == 80
assert listener['DefaultActions'][0]['TargetGroupArn'] == target_arn

db, = rds.describe_db_instances(DBInstanceIdentifier='lab-results')['DBInstances']
assert db['Engine'] == 'postgres' and not db['PubliclyAccessible']
assert db['StorageEncrypted'] and db['BackupRetentionPeriod'] == 7
assert db['DBName'] == 'labresults'
env = {entry['name']: entry['value'] for entry in app['environment']}
expected_url = f"jdbc:postgresql://{db['Endpoint']['Address']}:5432/{db['DBName']}"
assert env['DB_URL'] == expected_url and env['DB_USER'] == db['MasterUsername']
# The only address substitution: the emulated RDS endpoint has no SQL process.
env['DB_URL'] = f"jdbc:postgresql://db:5432/{db['DBName']}"
for entry in app['secrets']:
    arn, key, stage, version = entry['valueFrom'].rsplit(':', 3)
    assert not stage and not version and key == entry['name']
    secret = client('secretsmanager', 4566).get_secret_value(SecretId=arn)
    env[entry['name']] = json.loads(secret['SecretString'])[key]
assert set(env) == {'DB_URL', 'DB_USER', 'DB_PASSWORD', 'READER_PASSWORD', 'TECHNICIAN_PASSWORD'}
config = {'services': {
    'db': {'image': 'postgres:17-alpine', 'environment': {
        'POSTGRES_DB': db['DBName'], 'POSTGRES_USER': env['DB_USER'], 'POSTGRES_PASSWORD': env['DB_PASSWORD']},
        'healthcheck': {'test': ['CMD-SHELL', f"pg_isready -U {env['DB_USER']} -d {db['DBName']}"], 'interval': '3s', 'timeout': '3s', 'retries': 30},
        'mem_limit': '384m'},
    'app': {'image': app['image'], 'environment': env, 'mem_limit': '1g',
        'depends_on': {'db': {'condition': 'service_healthy'}},
        'healthcheck': {'test': app['healthCheck']['command'], 'interval': '5s', 'timeout': '5s', 'retries': 30, 'start_period': '30s'}},
    'alb': {'image': 'nginx:1.28-alpine', 'mem_limit': '64m',
        'ports': ['127.0.0.1:18080:80'],
        'volumes': [f'{out}/nginx.conf:/etc/nginx/conf.d/default.conf:ro'],
        'depends_on': {'app': {'condition': 'service_healthy'}},
        'healthcheck': {'test': ['CMD-SHELL', 'wget -q -O /dev/null http://127.0.0.1/health'], 'interval': '3s', 'timeout': '3s', 'retries': 20}}
}}
(out / 'compose.json').write_text(json.dumps(config))
(out / 'nginx.conf').write_text('server { listen 80; location / { proxy_pass http://app:8080; } }\n')
# Read credentials from a private file, never put them in CI logs or artifacts.
(out / 'smoke-env.json').write_text(json.dumps({k: v for k, v in env.items() if k.endswith('_PASSWORD')}))
print('Validated deployed ECS, RDS, ALB and LocalStack secret wiring; wrote private runtime configuration.')
