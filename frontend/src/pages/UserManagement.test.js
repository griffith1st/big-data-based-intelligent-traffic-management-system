import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import UserManagement from './UserManagement';
import { userApi } from '../services/api';
jest.mock('../services/api', () => ({ userApi: { getList: jest.fn(), getPage: jest.fn(), create: jest.fn(), update: jest.fn() }, isCancelled: () => false }));
let root, host;
beforeEach(() => {
  host = document.createElement('div'); document.body.appendChild(host); root = createRoot(host);
  userApi.getPage.mockResolvedValue({ code: 200, data: { content: [{ id: 3, username: 'driver3', realName: '李同学', role: 'DRIVER', enabled: true }], totalElements: 1, number: 0, size: 15, totalPages: 1 } });
});
afterEach(() => { act(() => root.unmount()); host.remove(); jest.clearAllMocks(); });
async function click(label) {
  const button = [...document.querySelectorAll('button')].find(item => item.textContent.replace(/\s/g, '') === label);
  await act(async () => button.dispatchEvent(new MouseEvent('click', { bubbles: true })));
}

test('creating a user visibly defaults to enabled', async () => {
  await act(async () => root.render(<UserManagement />));
  await click('新增用户');
  expect(document.querySelector('[role="switch"]').getAttribute('aria-checked')).toBe('true');
});

test('editing keeps username immutable and provides optional password reset', async () => {
  await act(async () => root.render(<UserManagement />));
  await click('编辑');
  expect(document.querySelector('input[placeholder="用户名"]').disabled).toBe(true);
  expect(document.querySelector('input[type="password"]')).not.toBeNull();
  expect(document.body.textContent).toContain('留空保留原密码');
});
