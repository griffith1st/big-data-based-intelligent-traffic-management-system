import React, { useEffect, useState } from 'react';
import { Card, Table, Tag, Button, Space, Spin, message, Row, Col, Statistic, Select, Modal, Descriptions } from 'antd';
import { WarningOutlined, InfoCircleOutlined, ExclamationCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import ReactEChartsCore from 'echarts-for-react';
import { alertApi } from '../services/api';

const levelConfig: Record<string, { label: string; color: string; icon: React.ReactNode }> = {
  INFO: { label: '信息', color: 'blue', icon: <InfoCircleOutlined /> },
  WARNING: { label: '警告', color: 'orange', icon: <ExclamationCircleOutlined /> },
  CRITICAL: { label: '严重', color: 'red', icon: <WarningOutlined /> },
};

const statusConfig: Record<string, { label: string; color: string }> = {
  UNREAD: { label: '未读', color: 'red' },
  READ: { label: '已读', color: 'orange' },
  RESOLVED: { label: '已处理', color: 'green' },
};

const typeLabels: Record<string, string> = {
  CONGESTION: '拥堵告警',
  ACCIDENT: '事故告警',
  SIGNAL: '信号故障',
  WEATHER: '天气影响',
};

const AlertManagement: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [alertList, setAlertList] = useState<any[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [statistics, setStatistics] = useState<any>({});
  const [detailVisible, setDetailVisible] = useState(false);
  const [currentAlert, setCurrentAlert] = useState<any>(null);
  const [filterLevel, setFilterLevel] = useState<string>('');

  const fetchData = async () => {
    try {
      const [listRes, countRes, statsRes] = await Promise.all([
        alertApi.getList(),
        alertApi.getUnreadCount(),
        alertApi.getStatistics(),
      ]);
      if (listRes.code === 200) setAlertList(listRes.data || []);
      if (countRes.code === 200) setUnreadCount(countRes.data || 0);
      if (statsRes.code === 200) setStatistics(statsRes.data || {});
    } catch (error: any) {
      message.error(error.message || '获取告警数据失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, []);

  const handleMarkRead = async (id: number) => {
    try {
      const res = await alertApi.markAsRead(id);
      if (res.code === 200) {
        message.success('已标记为已读');
        fetchData();
      }
    } catch (error: any) {
      message.error(error.message || '操作失败');
    }
  };

  const handleResolve = async (id: number) => {
    try {
      const userInfo = JSON.parse(localStorage.getItem('userInfo') || '{}');
      const res = await alertApi.resolve(id, userInfo.realName || userInfo.username || 'system');
      if (res.code === 200) {
        message.success('告警已处理');
        fetchData();
      }
    } catch (error: any) {
      message.error(error.message || '操作失败');
    }
  };

  const levelPieOption = {
    tooltip: { trigger: 'item' as const, formatter: '{b}: {c} 条 ({d}%)' },
    legend: { bottom: 0 },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      data: [
        { name: '信息', value: statistics.INFO || 0, itemStyle: { color: '#1677ff' } },
        { name: '警告', value: statistics.WARNING || 0, itemStyle: { color: '#faad14' } },
        { name: '严重', value: statistics.CRITICAL || 0, itemStyle: { color: '#ff4d4f' } },
      ],
      label: { show: true, formatter: '{b}\n{d}%' },
    }],
  };

  const trendBuckets = alertList.reduce((acc: Record<string, number>, alert: any) => {
    const key = alert.createdAt ? new Date(alert.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '-';
    acc[key] = (acc[key] || 0) + 1;
    return acc;
  }, {});

  const trendOption = {
    tooltip: { trigger: 'axis' as const },
    xAxis: { type: 'category' as const, data: Object.keys(trendBuckets) },
    yAxis: { type: 'value' as const, name: '告警数量' },
    series: [{ name: '告警趋势', type: 'line', smooth: true, data: Object.values(trendBuckets), areaStyle: { color: 'rgba(255,77,79,0.1)' }, lineStyle: { color: '#ff4d4f', width: 3 }, itemStyle: { color: '#ff4d4f' } }],
  };

  const columns = [
    {
      title: '告警等级', dataIndex: 'alertLevel', key: 'alertLevel', width: 100,
      render: (level: string) => {
        const config = levelConfig[level] || { label: level, color: 'default', icon: null };
        return <Tag icon={config.icon} color={config.color}>{config.label}</Tag>;
      },
      filters: Object.entries(levelConfig).map(([value, config]) => ({ text: config.label, value })),
      onFilter: (value: any, record: any) => record.alertLevel === value,
    },
    { title: '告警类型', dataIndex: 'alertType', key: 'alertType', width: 110, render: (type: string) => <Tag>{typeLabels[type] || type}</Tag> },
    { title: '标题', dataIndex: 'title', key: 'title', width: 180, ellipsis: true },
    { title: '内容', dataIndex: 'content', key: 'content', width: 250, ellipsis: true },
    { title: '路口', dataIndex: 'intersectionId', key: 'intersectionId', width: 100 },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 90,
      render: (status: string) => {
        const config = statusConfig[status] || { label: status, color: 'default' };
        return <Tag color={config.color}>{config.label}</Tag>;
      },
    },
    { title: '处理人', dataIndex: 'handler', key: 'handler', width: 100, render: (v: string) => v || '-' },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: (v: string) => v ? new Date(v).toLocaleString() : '', sorter: (a: any, b: any) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime(), defaultSortOrder: 'descend' as const },
    {
      title: '操作', key: 'action', width: 190,
      render: (_: any, record: any) => (
        <Space>
          <Button size="small" onClick={() => { setCurrentAlert(record); setDetailVisible(true); }}>详情</Button>
          {record.status === 'UNREAD' && <Button size="small" type="primary" onClick={() => handleMarkRead(record.id)}>标记已读</Button>}
          {record.status !== 'RESOLVED' && <Button size="small" type="primary" ghost onClick={() => handleResolve(record.id)}>处理</Button>}
        </Space>
      ),
    },
  ];

  if (loading) return <div style={{ textAlign: 'center', padding: 100 }}><Spin size="large" /></div>;

  return (
    <div>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="未处理告警" value={unreadCount} prefix={<WarningOutlined />} valueStyle={{ color: unreadCount > 0 ? '#ff4d4f' : '#52c41a' }} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="信息" value={statistics.INFO || 0} prefix={<InfoCircleOutlined />} valueStyle={{ color: '#1677ff' }} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="警告" value={statistics.WARNING || 0} prefix={<ExclamationCircleOutlined />} valueStyle={{ color: '#faad14' }} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="严重" value={statistics.CRITICAL || 0} prefix={<WarningOutlined />} valueStyle={{ color: '#ff4d4f' }} /></Card></Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}><Card title="告警等级分布" bodyStyle={{ padding: 12 }}><ReactEChartsCore option={levelPieOption} notMerge style={{ height: 300 }} /></Card></Col>
        <Col xs={24} lg={12}><Card title="告警趋势" bodyStyle={{ padding: 12 }}><ReactEChartsCore option={trendOption} notMerge style={{ height: 300 }} /></Card></Col>
      </Row>

      <Row style={{ marginTop: 16 }}>
        <Col span={24}>
          <Card
            title="告警列表"
            extra={<Space><Select placeholder="按等级筛选" style={{ width: 120 }} value={filterLevel} onChange={setFilterLevel} options={[{ label: '全部', value: '' }, { label: '信息', value: 'INFO' }, { label: '警告', value: 'WARNING' }, { label: '严重', value: 'CRITICAL' }]} /><Button icon={<ReloadOutlined />} onClick={fetchData}>刷新</Button></Space>}
          >
            <Table columns={columns} dataSource={filterLevel ? alertList.filter((a) => a.alertLevel === filterLevel) : alertList} rowKey="id" size="small" scroll={{ x: 1100, y: 400 }} pagination={{ pageSize: 15, showSizeChanger: true, showTotal: (t) => `共 ${t} 条告警` }} />
          </Card>
        </Col>
      </Row>

      <Modal title="告警详情" open={detailVisible} onCancel={() => setDetailVisible(false)} footer={null} width={600}>
        {currentAlert && (
          <Descriptions column={2} bordered size="small">
            <Descriptions.Item label="标题" span={2}>{currentAlert.title}</Descriptions.Item>
            <Descriptions.Item label="内容" span={2}>{currentAlert.content}</Descriptions.Item>
            <Descriptions.Item label="告警等级"><Tag color={levelConfig[currentAlert.alertLevel]?.color}>{levelConfig[currentAlert.alertLevel]?.label || currentAlert.alertLevel}</Tag></Descriptions.Item>
            <Descriptions.Item label="告警类型">{typeLabels[currentAlert.alertType] || currentAlert.alertType}</Descriptions.Item>
            <Descriptions.Item label="路口ID">{currentAlert.intersectionId}</Descriptions.Item>
            <Descriptions.Item label="状态"><Tag color={statusConfig[currentAlert.status]?.color}>{statusConfig[currentAlert.status]?.label || currentAlert.status}</Tag></Descriptions.Item>
            <Descriptions.Item label="处理人">{currentAlert.handler || '-'}</Descriptions.Item>
            <Descriptions.Item label="创建时间" span={2}>{currentAlert.createdAt ? new Date(currentAlert.createdAt).toLocaleString() : '-'}</Descriptions.Item>
            {currentAlert.handledAt && <Descriptions.Item label="处理时间" span={2}>{new Date(currentAlert.handledAt).toLocaleString()}</Descriptions.Item>}
          </Descriptions>
        )}
      </Modal>
    </div>
  );
};

export default AlertManagement;
