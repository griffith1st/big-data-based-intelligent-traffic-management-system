export type Role = 'ADMIN' | 'TRAFFIC_MANAGER' | 'DRIVER';
export interface SessionUser {
  userId: number;
  id?: number;
  username: string;
  realName?: string;
  role: Role;
  enabled?: boolean;
}

export const getToken = () => localStorage.getItem('token');
export const clearSession = () => {
  localStorage.removeItem('token');
  localStorage.removeItem('userInfo');
  window.dispatchEvent(new Event('itms:session'));
};

export function normalizeUser(value: any): SessionUser | null {
  if (!value || typeof value.username !== 'string' || !value.username.trim()
      || !['ADMIN', 'TRAFFIC_MANAGER', 'DRIVER'].includes(value.role)
      || !Number.isInteger(value.userId ?? value.id) || value.enabled === false) return null;
  return { userId: value.userId ?? value.id, username: value.username, realName: value.realName, role: value.role, enabled: value.enabled };
}

export function readSessionUser(): SessionUser | null {
  try { return normalizeUser(JSON.parse(localStorage.getItem('userInfo') || 'null')); }
  catch { return null; }
}

export function saveSession(token: string, user: SessionUser) {
  localStorage.setItem('token', token);
  // Keep the token in one place; cached identity is a display hint only.
  localStorage.setItem('userInfo', JSON.stringify(user));
  window.dispatchEvent(new Event('itms:session'));
}

export const canManageTraffic = (role?: Role) => role === 'ADMIN' || role === 'TRAFFIC_MANAGER';
export function canAccessPath(role: Role | undefined, path: string) {
  if (!role) return false;
  if (['/users', '/logs'].includes(path)) return role === 'ADMIN';
  if (['/signal', '/alert'].includes(path)) return canManageTraffic(role);
  return ['/dashboard', '/traffic', '/prediction', '/route'].includes(path);
}
