"""Verify the correction history survives replacement of the application process."""
import base64
import json
import os
import urllib.request

with open(os.environ['SMOKE_ENV_FILE']) as file:
    env = json.load(file)
headers = {'Authorization': 'Basic ' + base64.b64encode(('reader:' + env['READER_PASSWORD']).encode()).decode()}
base = os.environ['BASE_URL']
def read(path):
    with urllib.request.urlopen(urllib.request.Request(base + path, headers=headers), timeout=15) as response:
        return json.load(response)
results = read('/api/results?patientId=SYN-DEMO-002')
assert len(results) == 1
result, = results
assert result['revision'] == 2 and result['value'] == 4.3
history = read('/api/results/' + result['id'] + '/history')
assert [row['snapshot']['value'] for row in history] == [4.1, 4.3]
print('2 persistence checks passed after restarting the deployed app.')
