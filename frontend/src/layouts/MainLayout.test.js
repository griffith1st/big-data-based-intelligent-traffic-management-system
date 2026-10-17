import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { Modal } from 'antd';
import MainLayout from './MainLayout';
import { authApi } from '../services/api';

jest.mock('../services/AuthContext', () => ({ useAuth: () => ({ user: { userId: 1, username: 'admin', role: 'ADMIN' } }) }));
jest.mock('../services/api', () => ({ authApi: { logout: jest.fn() } }));
let root, host;
beforeEach(() => { host = document.createElement('div'); document.body.appendChild(host); root = createRoot(host); localStorage.setItem('token', 'still-valid'); });
afterEach(async () => { await act(async () => { Modal.destroyAll(); root.unmount(); }); host.remove(); localStorage.clear(); jest.clearAllMocks(); });

test('logout reports server failure and retains token until the user explicitly chooses local-only exit', async () => {
  authApi.logout.mockRejectedValue(Object.assign(new Error('服务繁忙'), { status: 503 }));
  await act(async () => root.render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><MainLayout /></MemoryRouter>));
  const accountButton = [...host.querySelectorAll('button')].find(node => node.textContent.includes('admin'));
  expect(accountButton).toBeDefined();
  await act(async () => accountButton.click());
  const logout = [...document.querySelectorAll('[role="menuitem"]')].find(node => node.textContent.includes('退出登录'));
  await act(async () => logout.click());
  expect(document.body.textContent).toContain('服务端退出尚未完成');
  expect(localStorage.getItem('token')).toBe('still-valid');
  const localExit = [...document.querySelectorAll('button')].find(node => node.textContent.includes('仅清除本地登录'));
  await act(async () => localExit.click());
  expect(localStorage.getItem('token')).toBeNull();
});
