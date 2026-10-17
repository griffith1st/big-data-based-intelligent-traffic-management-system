import { useEffect, useRef, useState } from 'react';
import { isCancelled } from './api';

/** One read at a time per resource; dependency changes/unmount cancel and fence old reads. */
export function useResource<T>(loader: (signal: AbortSignal) => Promise<T>, dependencies: unknown[] = [], interval = 0) {
  const [data, setData] = useState<T>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const [revision, setRevision] = useState(0);
  const loaderRef = useRef(loader);
  loaderRef.current = loader;
  // Dependencies are primitive identifiers (intersection/user); a stable key avoids effect restarts on rendering.
  const key = JSON.stringify(dependencies);
  const previousKey = useRef(key);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    if (previousKey.current !== key) { setData(undefined); setUpdatedAt(null); previousKey.current = key; }
    const run = async () => {
      setLoading(true);
      let delay = interval;
      try {
        const result = await loaderRef.current(controller.signal);
        if (!controller.signal.aborted) { setData(result); setError(''); setUpdatedAt(new Date()); }
      } catch (cause: any) {
        if (!controller.signal.aborted && !isCancelled(cause)) {
          setError(cause.message || '读取失败，请重试');
          delay = Math.max(interval, (cause.retryAfter || 0) * 1000);
        }
      } finally {
        if (!controller.signal.aborted) {
          setLoading(false);
          if (interval > 0) timer = setTimeout(run, delay);
        }
      }
    };
    run();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [key, interval, revision]);
  return { data, loading, error, updatedAt, reload: () => setRevision(v => v + 1) };
}
