import api, { trafficApi, authApi } from './api';

// Keep the real Axios pipeline; replace only its network transport.
jest.mock('axios', () => jest.requireActual('axios/dist/node/axios.cjs'));

afterEach(() => localStorage.clear());

test('overload errors preserve HTTP status and Retry-After for the page', async () => {
  api.defaults.adapter = async config => {
    throw { config, response: { status: 429, data: { code: 429, message: 'busy' }, headers: { 'retry-after': '3' } } };
  };
  await expect(trafficApi.getRealTime()).rejects.toMatchObject({ status: 429, retryAfter: 3 });
});

test('an HTTP 200 business failure rejects rather than looking like empty success', async () => {
  api.defaults.adapter = async config => ({ config, status: 200, headers: {}, data: { code: 403, message: 'forbidden', data: null } });
  await expect(trafficApi.getRealTime()).rejects.toMatchObject({ status: 403 });
});

test('403 keeps the current session instead of logging the user out', async () => {
  localStorage.setItem('token', 'test-token');
  api.defaults.adapter = async config => {
    throw { config, response: { status: 403, data: { message: 'forbidden' }, headers: {} } };
  };
  await expect(trafficApi.getRealTime()).rejects.toMatchObject({ status: 403 });
  expect(localStorage.getItem('token')).toBe('test-token');
});

test('GET cancellation signal reaches the request transport', async () => {
  const controller = new AbortController();
  let request;
  api.defaults.adapter = async config => {
    request = config;
    return { config, status: 200, headers: {}, data: { code: 200, data: [] } };
  };
  await trafficApi.getRealTime(controller.signal);
  expect(request.signal).toBe(controller.signal);
});

test('401 on protected API clears the session and notifies React authentication state', async () => {
  localStorage.setItem('token', 'expired');
  localStorage.setItem('userInfo', '{}');
  let notified = false;
  const listener = () => { notified = true; };
  window.addEventListener('itms:session', listener);
  api.defaults.adapter = async config => { throw { config, response: { status: 401, data: { message: 'expired' }, headers: {} } }; };
  await expect(trafficApi.getRealTime()).rejects.toMatchObject({ status: 401 });
  expect(localStorage.getItem('token')).toBeNull();
  expect(notified).toBe(true);
  window.removeEventListener('itms:session', listener);
});

test('invalid login credentials preserve the server explanation instead of claiming session expiry', async () => {
  api.defaults.adapter = async config => { throw { config, response: { status: 401, data: { message: '用户名或密码错误' }, headers: {} } }; };
  await expect(authApi.login('sample', 'wrong')).rejects.toMatchObject({ status: 401, message: '用户名或密码错误' });
});
