﻿/**
 * ITMS-BD 前端API服务
 * 封装所有后端接口调用
 */

import axios from 'axios';

const API_BASE_URL = process.env.REACT_APP_API_URL || 'http://localhost:8080';

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
        const token = localStorage.getItem('token');
        if (token) {
            config.headers.Authorization = `Bearer ${token}`;
        }
        return config;
    },
    (error) => Promise.reject(error)
);

api.interceptors.response.use(
    (response) => response.data as any,
    (error) => {
        if (error.response?.status === 401) {
            localStorage.removeItem('token');
            localStorage.removeItem('userInfo');
            window.location.href = '/login';
        }
        return Promise.reject(error.response?.data || error);
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
};

// ========== 实时交通数据接口 ==========
export const trafficApi = {
    getRealTime: (): Promise<ApiResponse<any[]>> => api.get('/api/traffic/realtime'),

    getSummary: (): Promise<ApiResponse<any>> => api.get('/api/traffic/summary'),

    getHistory: (intersectionId: string, minutes: number = 60, limit?: number): Promise<ApiResponse<any[]>> =>
        api.get('/api/traffic/history', {
            params: { intersectionId, minutes, limit },
        }),

    getHeatmap: (): Promise<ApiResponse<any[]>> => api.get('/api/traffic/heatmap'),

    generateOnce: (): Promise<ApiResponse<any[]>> => api.post('/api/traffic/simulator/generate-once'),
};

// ========== 交通预测接口 ==========
export const predictionApi = {
    getPredictions: (intersectionId: string): Promise<ApiResponse<any[]>> =>
        api.get(`/api/prediction/${intersectionId}`),

    getOverview: (): Promise<ApiResponse<any[]>> => api.get('/api/prediction/overview'),

    triggerPrediction: (): Promise<ApiResponse<any>> => api.post('/api/prediction/trigger'),

    triggerLstm: (): Promise<ApiResponse<any>> => api.post('/api/predictions/lstm/trigger'),

    getLatest: (intersectionId?: string): Promise<ApiResponse<any>> =>
        api.get('/api/prediction/latest', { params: { intersectionId } }),

    detectRisk: (): Promise<ApiResponse<any[]>> => api.get('/api/prediction/risk'),
};

// ========== 信号灯接口 ==========
export const signalApi = {
    getIntersections: (): Promise<ApiResponse<any[]>> =>
        api.get('/api/signals/intersections'),

    getCurrent: (intersectionId: string): Promise<ApiResponse<any>> =>
        api.get(`/api/signals/${intersectionId}/state`),

    getSignals: (intersectionId: string): Promise<ApiResponse<any[]>> =>
        api.get(`/api/signal/${intersectionId}`),

    optimize: (intersectionId: string, strategy?: string): Promise<ApiResponse<any>> =>
        api.post(`/api/signal/optimize/${intersectionId}`, null, {
            params: { strategy: strategy || 'BALANCED' },
        }),

    applyPlan: (intersectionId: string): Promise<ApiResponse<any>> =>
        api.post(`/api/signal/apply/${intersectionId}`),

    getOverview: (): Promise<ApiResponse<any[]>> => api.get('/api/signal/overview'),
};

// ========== 告警接口 ==========
export const alertApi = {
    getList: (): Promise<ApiResponse<any[]>> => api.get('/api/alert/list'),

    getUnread: (): Promise<ApiResponse<any[]>> => api.get('/api/alert/unread'),

    getUnreadCount: (): Promise<ApiResponse<number>> => api.get('/api/alert/unread-count'),

    markAsRead: (id: number): Promise<ApiResponse<void>> => api.put(`/api/alert/read/${id}`),

    resolve: (id: number, handler: string): Promise<ApiResponse<void>> =>
        api.put(`/api/alert/resolve/${id}`, null, {
            params: { handler },
        }),

    getStatistics: (): Promise<ApiResponse<any>> => api.get('/api/alert/statistics'),
};

// ========== 路径规划接口 ==========
export const routeApi = {
    getIntersections: (): Promise<ApiResponse<any[]>> => api.get('/api/routes/intersections'),

    getNetwork: (): Promise<ApiResponse<any>> => api.get('/api/routes/network'),

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

    getUserRoutes: (userId: number): Promise<ApiResponse<any[]>> =>
        api.get(`/api/route/history/${userId}`),

    getHistory: (userId?: number): Promise<ApiResponse<any[]>> =>
        api.get('/api/routes/history', { params: { userId } }),
};

// ========== 用户管理接口 ==========
export const userApi = {
    getList: (): Promise<ApiResponse<any[]>> => api.get('/api/users/list'),

    create: (data: any): Promise<ApiResponse<any>> => api.post('/api/users', data),

    update: (id: number, data: any): Promise<ApiResponse<any>> =>
        api.put(`/api/users/${id}`, data),

    remove: (id: number): Promise<ApiResponse<void>> => api.delete(`/api/users/${id}`),
};

// ========== 系统日志接口 ==========
export const logApi = {
    getList: (params?: { logType?: string; userId?: number }): Promise<ApiResponse<any[]>> =>
        api.get('/api/logs', { params }),

    getSummary: (): Promise<ApiResponse<any>> => api.get('/api/logs/summary'),
};

export default api;

