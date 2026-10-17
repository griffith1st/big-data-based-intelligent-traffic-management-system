import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import Prediction from './Prediction';
import SignalControl from './SignalControl';
import { predictionApi, signalApi } from '../services/api';
import { useAuth } from '../services/AuthContext';

jest.mock('../services/api', () => ({ predictionApi: { getLatest: jest.fn(), triggerLstm: jest.fn() }, signalApi: { getIntersections: jest.fn(), getCurrent: jest.fn(), optimize: jest.fn(), applyPlan: jest.fn() }, isCancelled: e => e.name === 'AbortError' }));
jest.mock('../services/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('echarts-for-react', () => ({ __esModule: true, default: ({ option }) => <div data-chart>{JSON.stringify(option)}</div> }));
let root, host;
beforeEach(() => {
  host = document.createElement('div'); document.body.appendChild(host); root = createRoot(host);
  useAuth.mockReturnValue({ user: { role: 'DRIVER', userId: 2, username: 'driver' } });
  predictionApi.getLatest.mockResolvedValue({ code: 200, data: { batchId: null, overview: [], items: [], risks: [], mode: 'SIMULATION' } });
  signalApi.getIntersections.mockResolvedValue({ code: 200, data: [{ intersectionId: 'INT-001', intersectionName: '测试路口' }] });
  signalApi.getCurrent.mockResolvedValue({ code: 200, data: { signals: [], candidateSignals: [], directionStates: [], executionMode: 'SIMULATION' } });
});
afterEach(() => { act(() => root.unmount()); host.remove(); jest.clearAllMocks(); });

test('driver sees an honest empty simulated forecast and no write action', async () => {
  await act(async () => root.render(<Prediction />));
  expect(host.textContent).toContain('模拟预测');
  expect(host.textContent).toContain('暂无预测');
  expect(host.textContent).not.toContain('置信度');
  expect([...host.querySelectorAll('button')].some(b => /触发|生成预测/.test(b.textContent))).toBe(false);
});

test('signal plan cannot be applied before a candidate exists', async () => {
  useAuth.mockReturnValue({ user: { role: 'TRAFFIC_MANAGER' } });
  await act(async () => root.render(<SignalControl />));
  const apply = [...host.querySelectorAll('button')].find(b => /应用/.test(b.textContent));
  expect(apply.disabled).toBe(true);
  expect(host.textContent).toContain('规则');
  expect(host.textContent).toContain('模拟');
});

test('optimization previews a candidate while current timing changes only after apply', async () => {
  const active = { id: 1, direction: 'NORTH', greenDuration: 30, yellowDuration: 3, redDuration: 87, cycleDuration: 120, strategy: 'BALANCED', optimizationStatus: 'DEFAULT' };
  const candidate = { ...active, greenDuration: 60, redDuration: 57, optimizationStatus: 'OPTIMIZED' };
  let phase = 'initial';
  signalApi.getCurrent.mockImplementation(async () => ({ code: 200, data: {
    signals: [phase === 'applied' ? candidate : active], candidateSignals: phase === 'optimized' ? [candidate] : [], directionStates: [], planValid: true,
  } }));
  signalApi.optimize.mockImplementation(async () => { phase = 'optimized'; return { code: 200, data: { signals: [candidate], improvementRate: 5 } }; });
  signalApi.applyPlan.mockImplementation(async () => { phase = 'applied'; return { code: 200, data: null }; });
  await act(async () => root.render(<SignalControl />));
  const button = text => [...host.querySelectorAll('button')].find(b => b.textContent.includes(text));
  const green = () => JSON.parse(host.querySelector('[data-chart]').textContent).series[0].data[0].value;
  expect(green()).toBe(30);
  await act(async () => button('计算候选配时').click());
  expect(green()).toBe(30);
  expect(button('应用候选方案').disabled).toBe(false);
  await act(async () => button('应用候选方案').click());
  expect(green()).toBe(60);
  expect(button('应用候选方案').disabled).toBe(true);
});
