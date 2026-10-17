import React, { createContext, useContext, useEffect, useState } from 'react';
import { authApi, isCancelled } from './api';
import { clearSession, getToken, normalizeUser, SessionUser } from './session';

const AuthContext = createContext<{ user: SessionUser | null; loading: boolean; error: string; refresh: () => void }>({ user: null, loading: true, error: '', refresh: () => {} });
export const useAuth = () => useContext(AuthContext);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<SessionUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const refresh = () => { setUser(null); setLoading(!!getToken()); setRevision(v => v + 1); };
    window.addEventListener('itms:session', refresh);
    const storage = (event: StorageEvent) => { if (!event.key || ['token', 'userInfo'].includes(event.key)) refresh(); };
    window.addEventListener('storage', storage);
    return () => { window.removeEventListener('itms:session', refresh); window.removeEventListener('storage', storage); };
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    setError('');
    if (!getToken()) { setUser(null); setLoading(false); return; }
    setLoading(true);
    authApi.me(controller.signal).then(response => {
      if (controller.signal.aborted) return;
      const profile = normalizeUser(response.data);
      if (!profile) { clearSession(); throw new Error('账号信息已失效，请重新登录'); }
      setUser(profile);
      localStorage.setItem('userInfo', JSON.stringify(profile));
    }).catch(cause => {
      if (!controller.signal.aborted && !isCancelled(cause)) { setUser(null); setError(cause.message || '身份验证失败，请重试'); }
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [revision]);
  return <AuthContext.Provider value={{ user, loading, error, refresh: () => setRevision(v => v + 1) }}>{children}</AuthContext.Provider>;
};
