"""Exercise a disposable/local demo deployment through its real frontend proxy."""
import argparse
import json
import os
import time
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen

parser = argparse.ArgumentParser()
parser.add_argument('--base-url', default='http://127.0.0.1:13000')
parser.add_argument('--backend-url', default='http://127.0.0.1:18080')
args = parser.parse_args()
password = os.environ.get('ITMS_SMOKE_PASSWORD', '123456')


def request(path, method='GET', body=None, token=None, status=200):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = Request(args.base_url + path, method=method, headers=headers,
                  data=None if body is None else json.dumps(body).encode())
    try:
        response = urlopen(req, timeout=15)
    except HTTPError as error:
        response = error
    with response:
        assert response.status == status, f'{method} {path}: HTTP {response.status}, expected {status}'
        result = json.load(response)
    assert result['code'] == status, f'{method} {path}: unexpected business code'
    return result.get('data')


with urlopen(args.backend_url + '/actuator/health', timeout=15) as response:
    assert json.load(response)['status'] == 'UP'
with urlopen(args.base_url + '/route', timeout=15) as response:
    assert '<html' in response.read().decode().lower(), 'SPA route must return HTML'

admin = request('/api/auth/login', 'POST', {'username': 'admin', 'password': password})['token']
assert request('/api/auth/me', token=admin)['role'] == 'ADMIN'
request('/api/traffic/simulator/stop', 'POST', token=admin)
sample = {'intersectionId': 'INT-001', 'direction': 'NORTH', 'vehicleCount': 100,
          'avgSpeed': 10, 'congestionLevel': 0, 'dataSource': 'DEPLOY_SMOKE',
          'eventId': 'smoke-' + str(uuid.uuid4())}
saved = request('/api/traffic/save', 'POST', sample, admin)
replayed = request('/api/traffic/save', 'POST', sample, admin)
assert saved['id'] == replayed['id'], 'Identical event replay must return the stored row'
request('/api/traffic/save', 'POST', {**sample, 'vehicleCount': 200}, admin, status=409)

generated = request('/api/traffic/simulator/generate-once', 'POST', token=admin)
assert len(generated) == 56
expected = {row['eventId'] for row in generated if row['intersectionId'] == 'INT-001'}
deadline = time.monotonic() + 30
while True:
    history = request('/api/traffic/history?intersectionId=INT-001&limit=100', token=admin)
    observed = {row.get('eventId') for row in history}
    realtime = request('/api/traffic/realtime', token=admin)
    if expected <= observed and len(realtime) == 56:
        break
    assert time.monotonic() < deadline, 'Generated events did not reach the database in time'
    time.sleep(0.5)

driver = request('/api/auth/login', 'POST', {'username': 'driver1', 'password': password})['token']
request('/api/users/page', token=driver, status=403)
request('/api/signals/INT-001/optimize', 'POST', token=driver, status=403)
for token in (driver, admin):
    request('/api/auth/logout', 'POST', token=token)
    request('/api/auth/me', token=token, status=401)
print('PASS: health, SPA, same-origin login, event replay/conflict, generated batch persistence, roles, logout revocation')
