import React from 'react';
import { createRoot } from 'react-dom/client';
import { act } from 'react';
import App from './App';
import { authApi } from './services/api';

jest.mock('./services/api', () => ({ authApi: { me: jest.fn() }, isCancelled: () => false }));
jest.mock('./layouts/MainLayout', () => () => require('react').createElement(require('react-router-dom').Outlet));
jest.mock('./pages/UserManagement', () => () => <div>PRIVATE_USER_EDITOR</div>);
jest.mock('./pages/Dashboard', () => () => <div>DASHBOARD</div>);
jest.mock('./pages/Login', () => () => <div>LOGIN</div>);

let container, root;
beforeEach(() => {
  global.IS_REACT_ACT_ENVIRONMENT = true;
  container = document.createElement('div'); document.body.appendChild(container); root = createRoot(container);
  localStorage.clear(); window.history.replaceState({}, '', '/users');
  localStorage.setItem('token', 'sample');
  localStorage.setItem('userInfo', JSON.stringify({ userId: 2, username: 'driver', role: 'ADMIN' }));
});
afterEach(() => { act(() => root.unmount()); container.remove(); jest.clearAllMocks(); });

test('cached ADMIN never grants access before the server confirms the actual DRIVER role', async () => {
  let finish; authApi.me.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  await act(async () => root.render(<App />));
  expect(container.textContent).not.toContain('PRIVATE_USER_EDITOR');
  await act(async () => finish({ code: 200, data: { userId: 2, username: 'driver', role: 'DRIVER', enabled: true } }));
  expect(container.textContent).not.toContain('PRIVATE_USER_EDITOR');
  expect(container.textContent).toContain('403');
});

test('confirmed ADMIN reaches administration even when cached JSON was damaged', async () => {
  localStorage.setItem('userInfo', '{broken');
  authApi.me.mockResolvedValue({ code: 200, data: { userId: 1, username: 'admin', role: 'ADMIN', enabled: true } });
  await act(async () => root.render(<App />));
  expect(container.textContent).toContain('PRIVATE_USER_EDITOR');
});

test('temporary identity service failure keeps token and offers retry without showing private pages', async () => {
  authApi.me.mockRejectedValue(new Error('服务繁忙'));
  await act(async () => root.render(<App />));
  expect(container.textContent).toContain('服务繁忙');
  expect(container.textContent).not.toContain('PRIVATE_USER_EDITOR');
  expect(localStorage.getItem('token')).toBe('sample');
});
