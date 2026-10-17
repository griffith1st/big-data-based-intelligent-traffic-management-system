import React from 'react';
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { useResource } from './useResource';

jest.mock('./api', () => ({ isCancelled: error => error.name === 'AbortError' }));
let root, host, latest;
function Probe({ load, selection }) {
  latest = useResource(signal => load(selection, signal), [selection], 5000);
  return <span>{latest.data ?? 'empty'}{latest.error}</span>;
}
beforeEach(() => { global.IS_REACT_ACT_ENVIRONMENT = true; jest.useFakeTimers(); host = document.createElement('div'); root = createRoot(host); });
afterEach(() => { act(() => root.unmount()); jest.useRealTimers(); });

test('slow requests finish before polling schedules another request', async () => {
  const waiting = [];
  const load = jest.fn(() => new Promise(resolve => waiting.push(resolve)));
  await act(async () => root.render(<Probe load={load} selection="A" />));
  await act(async () => jest.advanceTimersByTime(15000));
  expect(load).toHaveBeenCalledTimes(1);
  await act(async () => waiting[0]('first result'));
  expect(host.textContent).toBe('first result');
  await act(async () => jest.advanceTimersByTime(5000));
  expect(load).toHaveBeenCalledTimes(2);
});

test('selection changes abort old reads and an old response cannot replace the new selection', async () => {
  const requests = {};
  const load = (selection, signal) => new Promise(resolve => { requests[selection] = { signal, resolve }; });
  await act(async () => root.render(<Probe load={load} selection="A" />));
  await act(async () => root.render(<Probe load={load} selection="B" />));
  expect(requests.A.signal.aborted).toBe(true);
  await act(async () => requests.B.resolve('B data'));
  await act(async () => requests.A.resolve('stale A data'));
  expect(host.textContent).toBe('B data');
});

test('a refresh failure preserves last good data and records an error and successful timestamp', async () => {
  let failed = false;
  const load = async () => { if (failed) throw new Error('busy'); return 'retained'; };
  await act(async () => root.render(<Probe load={load} selection="A" />));
  const updatedAt = latest.updatedAt;
  failed = true;
  await act(async () => jest.advanceTimersByTime(5000));
  expect(latest.data).toBe('retained');
  expect(latest.error).toBe('busy');
  expect(latest.updatedAt).toBe(updatedAt);
});
