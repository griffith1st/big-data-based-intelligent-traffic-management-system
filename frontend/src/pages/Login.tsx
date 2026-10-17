import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Form, Input, Button, Card, message } from 'antd';
import { UserOutlined, LockOutlined, CarOutlined } from '@ant-design/icons';
import { authApi } from '../services/api';
import { normalizeUser, saveSession } from '../services/session';

const Login: React.FC = () => {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);

  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      const res = await authApi.login(values.username, values.password);
      if (res.code === 200) {
        const user = normalizeUser(res.data);
        if (!user || !res.data.token) throw new Error('登录响应缺少有效账号信息');
        saveSession(res.data.token, user);
        message.success(`欢迎回来，${res.data.realName || res.data.username}`);
        navigate('/dashboard', { replace: true });
      } else {
        message.error(res.message || '登录失败');
      }
    } catch (error: any) {
      message.error(error.message || '登录失败，请检查用户名和密码');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
      }}
    >
      <Card
        style={{ width: 420, borderRadius: 12, boxShadow: '0 8px 24px rgba(0,0,0,0.15)' }}
        styles={{ body: { padding: 40 } }}
      >
        <div style={{ textAlign: 'center', marginBottom: 32 }}>
          <CarOutlined style={{ fontSize: 48, marginBottom: 8, color: '#1677ff' }} />
          <h1 style={{ margin: 0, fontSize: 24, fontWeight: 600, color: '#1a1a2e' }}>
            ITMS-BD
          </h1>
          <p style={{ margin: '8px 0 0', color: '#666', fontSize: 14 }}>
            基于大数据的智能交通管理系统
          </p>
        </div>

        <Form
          name="login"
          onFinish={onFinish}
          size="large"
        >
          <Form.Item
            name="username"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input prefix={<UserOutlined />} placeholder="用户名" autoComplete="username" maxLength={50} />
          </Form.Item>

          <Form.Item
            name="password"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="密码" autoComplete="current-password" maxLength={72} />
          </Form.Item>

          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>
              登录
            </Button>
          </Form.Item>
        </Form>

        <div
          style={{
            textAlign: 'center',
            color: '#999',
            fontSize: 12,
            borderTop: '1px solid #f0f0f0',
            paddingTop: 16,
          }}
        >
          <p style={{ margin: '4px 0' }}>请使用管理员提供的账号登录；演示环境账号见部署文档。</p>
        </div>
      </Card>
    </div>
  );
};

export default Login;

