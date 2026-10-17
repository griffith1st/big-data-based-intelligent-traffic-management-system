import React, { useState } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Menu, Avatar, Dropdown, Button, theme, Modal, message } from 'antd';
import type { MenuProps } from 'antd';
import {
  DashboardOutlined,
  CarOutlined,
  BarChartOutlined,
  ApartmentOutlined,
  EnvironmentOutlined,
  AlertOutlined,
  UserOutlined,
  FileTextOutlined,
  LogoutOutlined,
} from '@ant-design/icons';
import { useAuth } from '../services/AuthContext';
import { canAccessPath, clearSession } from '../services/session';
import { authApi } from '../services/api';

const { Header, Sider, Content } = Layout;

const menuItems: MenuProps['items'] = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '实时监控大屏' },
  { key: '/traffic', icon: <CarOutlined />, label: '交通数据监测' },
  { key: '/prediction', icon: <BarChartOutlined />, label: '交通预测分析' },
  { key: '/signal', icon: <ApartmentOutlined />, label: '信号配时模拟' },
  { key: '/route', icon: <EnvironmentOutlined />, label: '路线规划' },
  { key: '/alert', icon: <AlertOutlined />, label: '告警管理' },
  { key: '/users', icon: <UserOutlined />, label: '用户管理' },
  { key: '/logs', icon: <FileTextOutlined />, label: '系统日志' },
];

const MainLayout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { token: { colorBgContainer } } = theme.useToken();

  const { user } = useAuth();
  const [collapsed, setCollapsed] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const username = user?.realName || user?.username || '用户';

  const leaveLocally = () => { clearSession(); navigate('/login', { replace: true }); };
  const handleLogout = async () => {
    if (loggingOut) return;
    setLoggingOut(true);
    try { await authApi.logout(); leaveLocally(); message.success('已退出登录'); }
    catch (error: any) {
      if (error.status === 401) { leaveLocally(); return; }
      Modal.confirm({ title: '服务端退出尚未完成', content: `${error.message}。可稍后重试，或仅清除当前浏览器登录信息；仅清除本地时，原令牌可能持续有效至过期。`, okText: '仅清除本地登录', cancelText: '保留登录以便重试', onOk: leaveLocally });
    } finally { setLoggingOut(false); }
  };

  const userDropdownItems: MenuProps['items'] = [
    { key: 'logout', icon: <LogoutOutlined />, label: loggingOut ? '正在退出...' : '退出登录', danger: true, disabled: loggingOut },
  ];

  const selectedKey = '/' + location.pathname.split('/')[1];

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        breakpoint="lg"
        collapsedWidth={80}
        collapsible
        collapsed={collapsed}
        onCollapse={setCollapsed}
        style={{ overflow: 'auto', height: '100vh', position: 'fixed', left: 0, top: 0, bottom: 0, zIndex: 100 }}
      >
        <div style={{ height: 64, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#fff', fontSize: 18, fontWeight: 'bold', borderBottom: '1px solid rgba(255,255,255,0.1)' }}>
          ITMS-BD
        </div>
        <Menu theme="dark" mode="inline" selectedKeys={[selectedKey]} items={menuItems?.filter(item => item && canAccessPath(user?.role, String(item.key)))} onClick={(e) => navigate(e.key)} />
      </Sider>
      <Layout style={{ marginLeft: collapsed ? 80 : 200, transition: 'margin-left 0.2s', minWidth: 0 }}>
        <Header style={{ padding: '0 24px', background: colorBgContainer, display: 'flex', justifyContent: 'flex-end', alignItems: 'center', borderBottom: '1px solid #f0f0f0', position: 'sticky', top: 0, zIndex: 99 }}>
          <Dropdown trigger={['click']} menu={{ items: userDropdownItems, onClick: (e) => e.key === 'logout' && handleLogout() }} placement="bottomRight">
            <Button type="text" style={{ height: 48 }}>
              <Avatar icon={<UserOutlined />} style={{ backgroundColor: '#1677ff' }} />
              <span>{username} · {user?.role === 'ADMIN' ? '系统管理员' : user?.role === 'TRAFFIC_MANAGER' ? '交通管理员' : '驾驶员'}</span>
            </Button>
          </Dropdown>
        </Header>
        <Content style={{ margin: 24, minHeight: 280 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
};

export default MainLayout;
