import React, { Suspense, lazy } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { Spin } from 'antd';
import MainLayout from './layouts/MainLayout';

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
    <Spin size="large" tip="加载中..." />
  </div>
);

// 简单的认证守卫
const isAuthenticated = (): boolean => {
  return !!localStorage.getItem('token');
};

const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  if (!isAuthenticated()) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
};

const App: React.FC = () => {
  return (
    <BrowserRouter>
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
    </BrowserRouter>
  );
};

export default App;

