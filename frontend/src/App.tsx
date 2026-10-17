import React, { Suspense, lazy } from 'react';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { Spin, Result, Button } from 'antd';
import MainLayout from './layouts/MainLayout';
import { AuthProvider, useAuth } from './services/AuthContext';
import { canAccessPath, getToken } from './services/session';

const Login = lazy(() => import('./pages/Login'));
const Dashboard = lazy(() => import('./pages/Dashboard'));
const TrafficMonitor = lazy(() => import('./pages/TrafficMonitor'));
const Prediction = lazy(() => import('./pages/Prediction'));
const SignalControl = lazy(() => import('./pages/SignalControl'));
const RoutePlanning = lazy(() => import('./pages/RoutePlanning'));
const AlertManagement = lazy(() => import('./pages/AlertManagement'));
const UserManagement = lazy(() => import('./pages/UserManagement'));
const SystemLogs = lazy(() => import('./pages/SystemLogs'));

const Loading = () => (
  <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
    <Spin size="large" /><span style={{ marginLeft: 12 }}>加载中...</span>
  </div>
);

const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, loading, error, refresh } = useAuth();
  const location = useLocation();
  if (!getToken()) {
    return <Navigate to="/login" replace />;
  }
  if (loading) return <Loading />;
  if (error) return <Result status="warning" title="身份验证暂未完成" subTitle={error} extra={<Button onClick={refresh}>重试</Button>} />;
  if (!user) return <Navigate to="/login" replace />;
  const path = '/' + location.pathname.split('/')[1];
  if (path !== '/' && !canAccessPath(user.role, path)) return <Result status="403" title="403" subTitle="当前账号没有此页面的访问权限" extra={<a href="/dashboard">返回仪表盘</a>} />;
  return <>{children}</>;
};

const App: React.FC = () => {
  return (
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <AuthProvider>
      <Suspense fallback={<Loading />}>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route
            path="/"
            element={
              <ProtectedRoute>
                <MainLayout />
              </ProtectedRoute>
            }
          >
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="traffic" element={<TrafficMonitor />} />
            <Route path="prediction" element={<Prediction />} />
            <Route path="signal" element={<SignalControl />} />
            <Route path="route" element={<RoutePlanning />} />
            <Route path="alert" element={<AlertManagement />} />
            <Route path="users" element={<UserManagement />} />
            <Route path="logs" element={<SystemLogs />} />
          </Route>
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </Suspense>
      </AuthProvider>
    </BrowserRouter>
  );
};

export default App;

