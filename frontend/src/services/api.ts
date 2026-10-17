﻿/**
 * ITMS-BD 前端API服务
 * 封装所有后端接口调用
 */

import axios from 'axios';
import { clearSession, getToken, SessionUser } from './session';

const API_BASE_URL = process.env.REACT_APP_API_URL || '';

export class ApiError extends Error {
    constructor(public status: number, message: string, public retryAfter?: number) {
        super(message);
        this.name = 'ApiError';
    }
}

export function normalizeApiError(error: any): ApiError {
    if (error instanceof ApiError) return error;
    const status = error.response?.status ?? error.status ?? error.code;
    const rawRetry = error.response?.headers?.['retry-after'];
    const seconds = rawRetry ? (/^\d+$/.test(String(rawRetry)) ? Number(rawRetry) : Math.max(0, Math.ceil((Date.parse(rawRetry) - Date.now()) / 1000))) : undefined;
    const retryAfter = Number.isFinite(seconds) ? seconds : undefined;
    const detail = error.response?.data?.message ?? error.message;
    const friendly = status === 429 ? '请求较多，请稍后重试' : status === 503 ? '服务繁忙，请稍后重试'
        : status === 401 ? '登录已过期，请重新登录' : status === 403 ? '当前账号没有此操作权限'
        : error.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : detail || '网络连接失败，请检查服务状态';
    return new ApiError(Number(status) || 0, `${friendly}${retryAfter !== undefined ? `（建议 ${retryAfter} 秒后）` : ''}`, retryAfter);
}

export const isCancelled = (error: any) => axios.isCancel(error) || error?.name === 'AbortError';

/** 后端统一响应格式 */
export interface ApiResponse<T = any> {
    code: number;
    data: T;
    message: string;
}

const api = axios.create({
    baseURL: API_BASE_URL,
    timeout: 8000,
    headers: {
        'Content-Type': 'application/json',
    },
});

api.interceptors.request.use(
    (config) => {
        const token = getToken();
        if (token) {
            config.headers.Authorization = `Bearer ${token}`;
        }
        return config;
    },
    (error) => Promise.reject(error)
);

const handleAuthFailure = (status: number, url?: string) => {
    if (status === 401 && !url?.includes('/auth/login')) {
        clearSession();
        window.dispatchEvent(new Event('itms:unauthorized'));
    }
};
api.interceptors.response.use(
    (response) => {
        if (response.data?.code !== undefined && response.data.code !== 200) {
            handleAuthFailure(response.data.code, response.config.url);
            return Promise.reject(normalizeApiError({ status: response.data.code, message: response.data.message }));
        }
        return response.data as any;
    },
    (error) => {
        if (isCancelled(error)) return Promise.reject(error);
        const normalized = error.response?.status === 401 && error.config?.url?.includes('/auth/login')
            ? new ApiError(401, error.response?.data?.message || '用户名或密码错误')
            : normalizeApiError(error);
        handleAuthFailure(normalized.status, error.config?.url);
        return Promise.reject(normalized);
    }
);

/** 带分页的列表响应 */
export interface PageResponse<T> {
    content: T[];
    totalElements: number;
    totalPages: number;
    size: number;
    number: number;
}

// ========== 认证接口 ==========
export const authApi = {
    login: (username: string, password: string): Promise<ApiResponse<{ token: string; username: string; realName: string; userId: number; role: string }>> =>
        api.post('/api/auth/login', { username, password }),

    logout: (): Promise<ApiResponse<void>> => api.post('/api/auth/logout'),
    me: (signal?: AbortSignal): Promise<ApiResponse<SessionUser>> => api.get('/api/auth/me', { signal }),
};

// ========== 实时交通数据接口 ==========
export const trafficApi = {
    getRealTime: (signal?: AbortSignal): Promise<ApiResponse<any[]>> => api.get('/api/traffic/realtime', { signal }),

    getSummary: (signal?: AbortSignal): Promise<ApiResponse<any>> => api.get('/api/traffic/summary', { signal }),

    getHistory: (intersectionId: string, minutes: number = 60, limit?: number, signal?: AbortSignal): Promise<ApiResponse<any[]>> =>
        api.get('/api/traffic/history', {
            params: { intersectionId, minutes, limit }, signal,
        }),

    getHeatmap: (signal?: AbortSignal): Promise<ApiResponse<any[]>> => api.get('/api/traffic/heatmap', { signal }),

    generateOnce: (): Promise<ApiResponse<any[]>> => api.post('/api/traffic/simulator/generate-once'),
};

// ========== 交通预测接口 ==========
export const predictionApi = {
    getPredictions: (intersectionId: string, signal?: AbortSignal): Promise<ApiResponse<any[]>> =>
        api.get(`/api/prediction/${intersectionId}`, { signal }),

    getOverview: (signal?: AbortSignal): Promise<ApiResponse<any[]>> => api.get('/api/prediction/overview', { signal }),

    triggerPrediction: (): Promise<ApiResponse<any>> => api.post('/api/prediction/trigger'),

    triggerLstm: (): Promise<ApiResponse<any>> => api.post('/api/predictions/lstm/trigger'),

    getLatest: (intersectionId?: string, signal?: AbortSignal): Promise<ApiResponse<any>> =>
        api.get('/api/prediction/latest', { params: { intersectionId }, signal }),

    detectRisk: (signal?: AbortSignal): Promise<ApiResponse<any[]>> => api.get('/api/prediction/risk', { signal }),
};

// ========== 信号灯接口 ==========
export const signalApi = {
    getIntersections: (signal?: AbortSignal): Promise<ApiResponse<any[]>> =>
        api.get('/api/signals/intersections', { signal }),

    getCurrent: (intersectionId: string, signal?: AbortSignal): Promise<ApiResponse<any>> =>
        api.get(`/api/signals/${intersectionId}/state`, { signal }),

    getSignals: (intersectionId: string, signal?: AbortSignal): Promise<ApiResponse<any[]>> =>
        api.get(`/api/signal/${intersectionId}`, { signal }),

    optimize: (intersectionId: string, strategy?: string): Promise<ApiResponse<any>> =>
        api.post(`/api/signal/optimize/${intersectionId}`, null, {
            params: { strategy: strategy || 'BALANCED' },
        }),

    applyPlan: (intersectionId: string): Promise<ApiResponse<any>> =>
        api.post(`/api/signal/apply/${intersectionId}`),

    getOverview: (signal?: AbortSignal): Promise<ApiResponse<any[]>> => api.get('/api/signal/overview', { signal }),
};

// ========== 告警接口 ==========
export const alertApi = {
    getList: (signal?: AbortSignal): Promise<ApiResponse<any[]>> => api.get('/api/alert/list', { signal }),

    getUnread: (signal?: AbortSignal): Promise<ApiResponse<any[]>> => api.get('/api/alert/unread', { signal }),

    getUnreadCount: (signal?: AbortSignal): Promise<ApiResponse<number>> => api.get('/api/alert/unread-count', { signal }),

    markAsRead: (id: number): Promise<ApiResponse<void>> => api.put(`/api/alert/read/${id}`),

    resolve: (id: number, handler: string): Promise<ApiResponse<void>> =>
        api.put(`/api/alert/resolve/${id}`, null, {
            params: { handler },
        }),

    getStatistics: (signal?: AbortSignal): Promise<ApiResponse<any>> => api.get('/api/alert/statistics', { signal }),
};

// ========== 路径规划接口 ==========
export const routeApi = {
    getIntersections: (signal?: AbortSignal): Promise<ApiResponse<any[]>> => api.get('/api/routes/intersections', { signal }),

    getNetwork: (signal?: AbortSignal): Promise<ApiResponse<any>> => api.get('/api/routes/network', { signal }),

    planByIntersection: (data: {
        startIntersectionId: string;
        endIntersectionId: string;
        strategy: string;
        userId?: number;
    }): Promise<ApiResponse<any>> => api.post('/api/routes/plan', data),

    planRoute: (params: {
        startLng: number;
        startLat: number;
        endLng: number;
        endLat: number;
        strategy: string;
        userId?: number;
    }): Promise<ApiResponse<any>> => api.post('/api/route/plan', null, { params }),

    planGreenRoute: (params: {
        startLng: number;
        startLat: number;
        endLng: number;
        endLat: number;
        userId?: number;
    }): Promise<ApiResponse<any>> => api.post('/api/route/green', null, { params }),

    getUserRoutes: (userId: number, signal?: AbortSignal): Promise<ApiResponse<any[]>> =>
        api.get(`/api/route/history/${userId}`, { signal }),

    getHistory: (userId?: number, signal?: AbortSignal): Promise<ApiResponse<any[]>> =>
        api.get('/api/routes/history', { params: { userId }, signal }),
};

// ========== 用户管理接口 ==========
export const userApi = {
    getPage: (page = 0, size = 15, signal?: AbortSignal): Promise<ApiResponse<PageResponse<any>>> => api.get('/api/users/page', { params: { page, size }, signal }),
    getList: (signal?: AbortSignal): Promise<ApiResponse<any[]>> => api.get('/api/users/list', { signal }),

    create: (data: any): Promise<ApiResponse<any>> => api.post('/api/users', data),

    update: (id: number, data: any): Promise<ApiResponse<any>> =>
        api.put(`/api/users/${id}`, data),

    remove: (id: number): Promise<ApiResponse<void>> => api.delete(`/api/users/${id}`),
};

// ========== 系统日志接口 ==========
export const logApi = {
    getPage: (params: { logType?: string; userId?: number; page?: number; size?: number }, signal?: AbortSignal): Promise<ApiResponse<PageResponse<any>>> => api.get('/api/logs/page', { params, signal }),
    getList: (params?: { logType?: string; userId?: number }, signal?: AbortSignal): Promise<ApiResponse<any[]>> =>
        api.get('/api/logs', { params, signal }),

    getSummary: (signal?: AbortSignal): Promise<ApiResponse<any>> => api.get('/api/logs/summary', { signal }),
};

export default api;

