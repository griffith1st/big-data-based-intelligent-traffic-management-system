import React, { useState } from 'react';
import { Card, Table, Tag, Button, Space, message, Row, Col, Statistic, Modal, Form, Input, Select, Switch, Popconfirm } from 'antd';
import { UserOutlined, PlusOutlined, EditOutlined, DeleteOutlined, ReloadOutlined, CrownOutlined, CarOutlined, SettingOutlined } from '@ant-design/icons';
import { userApi } from '../services/api';
import { useResource } from '../services/useResource';
import ResourceStatus from '../components/ResourceStatus';

const UserManagement: React.FC = () => {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(15);
  const resource = useResource(signal => userApi.getPage(page - 1, pageSize, signal).then(r => r.data), [page, pageSize]);
  const users = resource.data?.content || [];
  const fetchUsers = resource.reload;
  const [saving, setSaving] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingUser, setEditingUser] = useState<any>(null);
  const [form] = Form.useForm();

  const handleAdd = () => {
    setEditingUser(null);
    form.resetFields();
    form.setFieldsValue({ enabled: true, role: "DRIVER" });
    setModalVisible(true);
  };

  const handleEdit = (record: any) => {
    setEditingUser(record);
    form.resetFields();
    form.setFieldsValue({ ...record, password: undefined });
    setModalVisible(true);
  };

  const handleDelete = async (id: number) => {
    try {
      const res = await userApi.remove(id);
      if (res.code === 200) {
        message.success('用户已删除');
        if (users.length === 1 && page > 1) setPage(page - 1);
        else fetchUsers();
      } else {
        message.error(res.message || '删除失败');
      }
    } catch (error: any) {
      message.error(error.message || '删除失败');
    }
  };
  const handleSave = async () => {
    let values;
    try { values = await form.validateFields(); } catch { return; }
    setSaving(true);
    try {
      const payload = { ...values, enabled: values.enabled ?? true };
      if (editingUser && !payload.password) delete payload.password;
      await (editingUser ? userApi.update(editingUser.id, payload) : userApi.create(payload));
      message.success(editingUser ? '用户信息已更新' : '用户已创建');
      setModalVisible(false); fetchUsers();
    } catch (error: any) { message.error(error.message || '保存失败'); }
    finally { setSaving(false); }
  };
  const roleIcons: Record<string, React.ReactNode> = {
    ADMIN: <CrownOutlined style={{ color: '#faad14' }} />,
    TRAFFIC_MANAGER: <SettingOutlined style={{ color: '#1677ff' }} />,
    DRIVER: <CarOutlined style={{ color: '#52c41a' }} />,
  };

  const roleLabels: Record<string, string> = {
    ADMIN: '系统管理员',
    TRAFFIC_MANAGER: '交通管理员',
    DRIVER: '驾驶员',
  };

  const roleColors: Record<string, string> = {
    ADMIN: 'gold',
    TRAFFIC_MANAGER: 'blue',
    DRIVER: 'green',
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 60 },
    { title: '用户名', dataIndex: 'username', key: 'username', width: 100 },
    { title: '姓名', dataIndex: 'realName', key: 'realName', width: 100 },
    { title: '邮箱', dataIndex: 'email', key: 'email', width: 160, ellipsis: true },
    { title: '电话', dataIndex: 'phone', key: 'phone', width: 120 },
    {
      title: '角色',
      dataIndex: 'role',
      key: 'role',
      width: 120,
      render: (role: string) => (
        <Tag icon={roleIcons[role]} color={roleColors[role]}>
          {roleLabels[role] || role}
        </Tag>
      ),
    },
    {
      title: '管辖区域',
      dataIndex: 'district',
      key: 'district',
      width: 100,
      render: (v: string) => v || '-',
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 80,
      render: (enabled: boolean) => (
        <Tag color={enabled ? 'green' : 'red'}>{enabled ? '启用' : '禁用'}</Tag>
      ),
    },
    {
      title: '上次登录',
      dataIndex: 'lastLoginTime',
      key: 'lastLoginTime',
      width: 160,
      render: (v: string) => (v ? new Date(v).toLocaleString() : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 150,
      render: (_: any, record: any) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            编辑
          </Button>
          <Popconfirm title="确定删除该用户?" onConfirm={() => handleDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const stats = {
    total: resource.data?.totalElements ?? 0,
    admins: users.filter((u) => u.role === 'ADMIN').length,
    managers: users.filter((u) => u.role === 'TRAFFIC_MANAGER').length,
    drivers: users.filter((u) => u.role === 'DRIVER').length,
  };


  return (
    <div>
      <ResourceStatus resource={resource} />
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Card><Statistic title="用户总数" value={stats.total} prefix={<UserOutlined />} valueStyle={{ color: '#1677ff' }} /></Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card><Statistic title="当前页管理员" value={stats.admins} prefix={<CrownOutlined />} valueStyle={{ color: '#faad14' }} /></Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card><Statistic title="当前页交通管理员" value={stats.managers} prefix={<SettingOutlined />} valueStyle={{ color: '#1677ff' }} /></Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card><Statistic title="当前页驾驶员" value={stats.drivers} prefix={<CarOutlined />} valueStyle={{ color: '#52c41a' }} /></Card>
        </Col>
      </Row>

      <Row style={{ marginTop: 16 }}>
        <Col span={24}>
          <Card
            title="用户管理"
            extra={
              <Space>
                <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>
                  新增用户
                </Button>
                <Button icon={<ReloadOutlined />} onClick={fetchUsers}>
                  刷新
                </Button>
              </Space>
            }
          >
            <Table
              loading={resource.loading}
              columns={columns}
              dataSource={users}
              rowKey="id"
              size="small"
              scroll={{ x: 1000, y: 400 }}
              pagination={{ current: page, pageSize, total: resource.data?.totalElements || 0, onChange: (next, size) => { setPage(size !== pageSize ? 1 : next); setPageSize(size); }, showSizeChanger: true, showTotal: (t) => `共 ${t} 个用户` }}
            />
          </Card>
        </Col>
      </Row>

      <Modal
        title={editingUser ? '编辑用户' : '新增用户'}
        open={modalVisible}
        onOk={handleSave}
        confirmLoading={saving}
        onCancel={() => !saving && setModalVisible(false)}
        width={500}
      >
        <Form form={form} layout="vertical" initialValues={{ enabled: true, role: "DRIVER" }}>
          <Form.Item name="username" label="用户名" rules={[{ required: true, whitespace: true, message: "请输入用户名" }, { pattern: /^[A-Za-z0-9_.-]{3,50}$/, message: "使用3-50位字母、数字、点、下划线或短横线" }]}>
            <Input placeholder="用户名" disabled={!!editingUser} maxLength={50} />
          </Form.Item>
          <Form.Item name="password" label={editingUser ? "新密码（留空保留原密码）" : "密码"} rules={[{ required: !editingUser, message: '请输入密码' }, { min: 6, max: 72, message: '密码为6-72个字符' }]}>
            <Input.Password placeholder={editingUser ? "留空保留原密码" : "密码"} autoComplete="new-password" maxLength={72} />
          </Form.Item>
          <Form.Item name="realName" label="真实姓名" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input placeholder="真实姓名" maxLength={100} />
          </Form.Item>
          <Form.Item name="email" label="邮箱" rules={[{ type: 'email', message: '请输入有效邮箱' }]}>
            <Input placeholder="邮箱" maxLength={100} />
          </Form.Item>
          <Form.Item name="phone" label="电话" rules={[{ pattern: /^[+0-9() -]{0,20}$/, message: "请输入有效联系电话（最多20字符）" }]}>
            <Input placeholder="电话" maxLength={20} />
          </Form.Item>
          <Form.Item name="role" label="角色" rules={[{ required: true, message: '请选择角色' }]}>
            <Select
              options={[
                { label: '系统管理员', value: 'ADMIN' },
                { label: '交通管理员', value: 'TRAFFIC_MANAGER' },
                { label: '驾驶员', value: 'DRIVER' },
              ]}
            />
          </Form.Item>
          <Form.Item name="district" label="管辖区域">
            <Input placeholder="管辖区域" maxLength={100} />
          </Form.Item>
          <Form.Item name="enabled" label="状态" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="禁用" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default UserManagement;




