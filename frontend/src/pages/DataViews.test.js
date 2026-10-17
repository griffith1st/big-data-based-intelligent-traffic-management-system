import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import Dashboard from './Dashboard';
import RoutePlanning from './RoutePlanning';
import SystemLogs from './SystemLogs';
import TrafficMonitor from './TrafficMonitor';
import { trafficApi, predictionApi, alertApi, routeApi, logApi } from '../services/api';

jest.mock('../services/api', () => ({ trafficApi: { getRealTime: jest.fn(), getHeatmap: jest.fn() }, predictionApi: { getOverview: jest.fn() }, alertApi: { getUnreadCount: jest.fn() }, routeApi: { getIntersections: jest.fn(), getNetwork: jest.fn(), getHistory: jest.fn(), planByIntersection: jest.fn() }, logApi: { getPage: jest.fn(), getSummary: jest.fn() }, isCancelled: () => false }));
jest.mock('../services/AuthContext', () => ({ useAuth: () => ({ user: { role: 'ADMIN', userId: 1, username: 'admin' } }) }));
jest.mock('echarts-for-react', () => ({ __esModule: true, default: ({ option }) => <div data-chart>{JSON.stringify(option)}</div> }));
let root, host;
const ok = data => ({ code: 200, data });
beforeEach(() => {
  host = document.createElement('div'); document.body.appendChild(host); root = createRoot(host);
  trafficApi.getRealTime.mockResolvedValue(ok([])); trafficApi.getHeatmap.mockResolvedValue(ok([]));
  predictionApi.getOverview.mockResolvedValue(ok([])); alertApi.getUnreadCount.mockResolvedValue(ok(0));
});
afterEach(() => { act(() => root.unmount()); host.remove(); jest.clearAllMocks(); });

test('dashboard keeps successful traffic data when the independent prediction block fails', async () => {
  trafficApi.getRealTime.mockResolvedValue(ok([{ id: 1, intersectionId: 'INT-001', intersectionName: '仍然显示的路口', direction: 'NORTH', vehicleCount: 10, avgSpeed: 20, congestionLevel: 1 }]));
  predictionApi.getOverview.mockRejectedValue(new Error('预测服务暂时繁忙'));
  await act(async () => root.render(<Dashboard />));
  expect(host.textContent).toContain('仍然显示的路口');
  expect(host.textContent).toContain('预测服务暂时繁忙');
  expect(host.textContent).toContain('最后成功获取');
});

test('route graph marks absent traffic samples unknown and never invents a sampling timestamp', async () => {
  routeApi.getIntersections.mockResolvedValue(ok([{ intersectionId: 'A', intersectionName: '甲', longitude: 116, latitude: 39 }, { intersectionId: 'B', intersectionName: '乙', longitude: 117, latitude: 40 }]));
  routeApi.getNetwork.mockResolvedValue(ok({ edges: [{ from: 'A', to: 'B', distanceKm: 1 }] }));
  routeApi.getHistory.mockResolvedValue(ok([]));
  await act(async () => root.render(<RoutePlanning />));
  const chart = JSON.parse(host.querySelector('[data-chart]').textContent);
  expect(chart.series[0].data[0].edgeInfo.congestionLevel).toBe(-1);
  expect(host.textContent).toContain('暂无采样时间');
});

test('route graph does not label expired observations as current congestion', async () => {
  routeApi.getIntersections.mockResolvedValue(ok([{ intersectionId: 'A', intersectionName: '甲', longitude: 116, latitude: 39 }, { intersectionId: 'B', intersectionName: '乙', longitude: 117, latitude: 40 }]));
  routeApi.getNetwork.mockResolvedValue(ok({ edges: [{ from: 'A', to: 'B', distanceKm: 1 }] }));
  routeApi.getHistory.mockResolvedValue(ok([]));
  trafficApi.getRealTime.mockResolvedValue(ok([{ intersectionId: 'A', congestionLevel: 0, recordTime: '2020-01-01T10:00:00' }, { intersectionId: 'B', congestionLevel: 0, recordTime: '2020-01-01T10:00:00' }]));
  await act(async () => root.render(<RoutePlanning />));
  expect(JSON.parse(host.querySelector('[data-chart]').textContent).series[0].data[0].edgeInfo.congestionLevel).toBe(-1);
});

test('planned path preserves unknown sampling in its tooltip instead of borrowing a newer smooth map', async () => {
  const nodes = [{ intersectionId: 'A', intersectionName: '甲', longitude: 116, latitude: 39 }, { intersectionId: 'B', intersectionName: '乙', longitude: 117, latitude: 40 }];
  routeApi.getIntersections.mockResolvedValue(ok(nodes));
  routeApi.getNetwork.mockResolvedValue(ok({ edges: [{ from: 'A', to: 'B', distanceKm: 1 }] }));
  routeApi.getHistory.mockResolvedValue(ok([]));
  trafficApi.getRealTime.mockResolvedValue(ok(nodes.map(n => ({ ...n, congestionLevel: 0, recordTime: new Date().toISOString() }))));
  routeApi.planByIntersection.mockResolvedValue(ok({ routePoints: JSON.stringify(nodes), routeEdges: JSON.stringify([{ from: 'A', to: 'B', distanceKm: 1, congestionLevel: null, trafficDataStatus: 'PARTIAL' }]), trafficDataStatus: 'PARTIAL' }));
  await act(async () => root.render(<RoutePlanning />));
  await act(async () => host.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
  const chart = JSON.parse(host.querySelector('[data-chart]').textContent);
  expect(chart.series[1].data[0].edgeInfo.congestionLabel).toBe('未知（缺少采样）');
});

test('route history names canonical and legacy low carbon strategies consistently', async () => {
  routeApi.getIntersections.mockResolvedValue(ok([]));
  routeApi.getNetwork.mockResolvedValue(ok({ edges: [] }));
  routeApi.getHistory.mockResolvedValue(ok([{ id: 1, strategy: 'LOW_CARBON' }, { id: 2, strategy: 'GREENEST' }]));
  await act(async () => root.render(<RoutePlanning />));
  const rows = host.querySelector('.ant-table-tbody').textContent;
  expect(rows.match(/低碳路径/g)).toHaveLength(2);
  expect(rows).not.toContain('LOW_CARBON');
  expect(rows).not.toContain('GREENEST');
});

test('logs use server pagination while the full summary retains ordered calendar dates', async () => {
  logApi.getPage.mockImplementation(async params => ok({ content: [{ id: params.page + 1, description: params.page ? '第二页记录' : '第一页记录', logType: 'LOGIN', status: 'SUCCESS' }], totalElements: 40, size: 20, number: params.page, totalPages: 2 }));
  logApi.getSummary.mockResolvedValue(ok({ totalCount: 40, byType: { LOGIN: 40 }, failedCount: 0, byDay: { '2027-01-01': 2, '2026-12-31': 1 } }));
  await act(async () => root.render(<SystemLogs />));
  const chart = JSON.parse(host.querySelectorAll('[data-chart]')[1].textContent);
  expect(chart.xAxis.data).toEqual(['2026-12-31', '2027-01-01']);
  await act(async () => host.querySelector('.ant-pagination-item-2').click());
  expect(host.textContent).toContain('第二页记录');
  expect(host.textContent).not.toContain('第一页记录');
  expect(JSON.parse(host.querySelectorAll('[data-chart]')[1].textContent).series[0].data).toEqual([1, 2]);
});

test('traffic selection survives a manual background refresh', async () => {
  trafficApi.getRealTime.mockResolvedValue(ok([{ id: 1, intersectionId: 'A', intersectionName: '甲路口', direction: 'NORTH' }, { id: 2, intersectionId: 'B', intersectionName: '乙路口', direction: 'NORTH' }]));
  await act(async () => root.render(<TrafficMonitor />));
  await act(async () => host.querySelector('.ant-select-selector').dispatchEvent(new MouseEvent('mousedown', { bubbles: true })));
  const option = [...document.querySelectorAll('.ant-select-item-option-content')].find(node => node.textContent === '乙路口');
  await act(async () => option.click());
  expect(host.querySelector('.ant-select-selection-item').textContent).toBe('乙路口');
  const refresh = [...host.querySelectorAll('button')].filter(node => node.textContent.replace(/\s/g, '') === '刷新');
  await act(async () => refresh[refresh.length - 1].click());
  expect(host.querySelector('.ant-select-selection-item').textContent).toBe('乙路口');
  expect(host.querySelector('.ant-table-tbody').textContent).not.toContain('甲路口');
});
