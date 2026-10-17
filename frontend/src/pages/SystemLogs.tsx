import React, { useState } from 'react';
import { Card, Table, Tag, Button, Space, Row, Col, Statistic, Select } from 'antd';
import { FileTextOutlined, LoginOutlined, CodeOutlined, WarningOutlined, CheckCircleOutlined, CloseCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import ReactEChartsCore from 'echarts-for-react';
import { logApi } from '../services/api';
import { useResource } from '../services/useResource';
import ResourceStatus from '../components/ResourceStatus';

const SystemLogs: React.FC = () => {
  const [filterType, setFilterType] = useState<string>('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const resource = useResource(signal => logApi.getPage({ logType: filterType || undefined, page: page - 1, size: pageSize }, signal).then(r => r.data), [filterType, page, pageSize]);
  const aggregate = useResource(signal => logApi.getSummary(signal).then(r => r.data));
  const logs = resource.data?.content || [];
  const summary = aggregate.data;
  const fetchLogs = () => { resource.reload(); aggregate.reload(); };

  // 日志类型分布
  const typePieOption = {
    tooltip: { trigger: 'item' as const, formatter: '{b}: {c} 条 ({d}%)' },
    legend: { bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        data: [
          { name: '登录日志', value: summary?.byType?.LOGIN || 0, itemStyle: { color: '#1677ff' } },
          { name: '操作日志', value: summary?.byType?.OPERATION || 0, itemStyle: { color: '#52c41a' } },
          { name: '系统日志', value: summary?.byType?.SYSTEM || 0, itemStyle: { color: '#faad14' } },
          { name: '错误日志', value: summary?.byType?.ERROR || 0, itemStyle: { color: '#ff4d4f' } },
        ],
        label: { show: true, formatter: '{b}\n{d}%' },
      },
    ],
  };

  const trendBuckets: Record<string, number> = summary?.byDay || {};
  const trendDays = Object.keys(trendBuckets).sort();

  // 每日日志趋势
  const trendOption = {
    tooltip: { trigger: 'axis' as const },
    xAxis: { type: 'category' as const, data: trendDays },
    yAxis: { type: 'value' as const, name: '日志数量' },
    series: [
      {
        name: '日志量',
        type: 'bar',
        data: trendDays.map(day => trendBuckets[day]),
        itemStyle: { color: '#1677ff', borderRadius: [4, 4, 0, 0] },
      },
      {
        name: '趋势',
        type: 'line',
        smooth: true,
        data: trendDays.map(day => trendBuckets[day]),
        lineStyle: { color: '#fa8c16', width: 2 },
        itemStyle: { color: '#fa8c16' },
        symbol: 'circle',
        symbolSize: 6,
      },
    ],
  };

  const logTypeConfig: Record<string, { label: string; icon: React.ReactNode; color: string }> = {
    LOGIN: { label: '登录日志', icon: <LoginOutlined />, color: 'blue' },
    OPERATION: { label: '操作日志', icon: <CodeOutlined />, color: 'green' },
    SYSTEM: { label: '系统日志', icon: <WarningOutlined />, color: 'orange' },
    ERROR: { label: '错误日志', icon: <CloseCircleOutlined />, color: 'red' },
  };

  const columns = [
    {
      title: '日志类型',
      dataIndex: 'logType',
      key: 'logType',
      width: 100,
      render: (type: string) => {
        const config = logTypeConfig[type] || { label: type, icon: <FileTextOutlined />, color: 'default' };
        return <Tag icon={config.icon} color={config.color}>{config.label}</Tag>;
      },
    },
    { title: '用户名', dataIndex: 'username', key: 'username', width: 100 },
    { title: '模块', dataIndex: 'module', key: 'module', width: 100 },
    { title: '描述', dataIndex: 'description', key: 'description', width: 280, ellipsis: true },
    { title: 'IP 地址', dataIndex: 'ipAddress', key: 'ipAddress', width: 130 },
    { title: '请求方法', dataIndex: 'method', key: 'method', width: 70 },
    { title: '请求 URI', dataIndex: 'requestUri', key: 'requestUri', width: 200, ellipsis: true },
    {
      title: '执行时间(ms)',
      dataIndex: 'executionTime',
      key: 'executionTime',
      width: 100,
      render: (v: number) => v ?? '-',
      sorter: (a: any, b: any) => (a.executionTime || 0) - (b.executionTime || 0),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 80,
      render: (status: string) => (
        <Tag icon={status === 'SUCCESS' ? <CheckCircleOutlined /> : <CloseCircleOutlined />} color={status === 'SUCCESS' ? 'success' : 'error'}>
          {status === 'SUCCESS' ? '成功' : '失败'}
        </Tag>
      ),
    },
    {
      title: '时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (v: string) => (v ? new Date(v).toLocaleString() : ''),
      sorter: (a: any, b: any) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime(),
      defaultSortOrder: 'descend' as const,
    },
  ];


  const errorCount = summary?.failedCount ?? 0;
  const totalCount = summary?.totalCount ?? 0;

  return (
    <div>
      <ResourceStatus title="全量日志统计（趋势为最近7天）" resource={aggregate} />
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Card><Statistic title="总日志量" value={totalCount} prefix={<FileTextOutlined />} valueStyle={{ color: '#1677ff' }} /></Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card><Statistic title="操作日志" value={summary?.byType?.OPERATION || 0} prefix={<CodeOutlined />} valueStyle={{ color: '#52c41a' }} /></Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card><Statistic title="登录日志" value={summary?.byType?.LOGIN || 0} prefix={<LoginOutlined />} valueStyle={{ color: '#1677ff' }} /></Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card>
            <Statistic
              title="失败记录"
              value={errorCount}
              prefix={<CloseCircleOutlined />}
              valueStyle={{ color: errorCount > 0 ? '#ff4d4f' : '#52c41a' }}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card title="日志类型分布" styles={{ body: { padding: 12 } }}>
            <ReactEChartsCore option={typePieOption} notMerge style={{ height: 300 }} />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="日志趋势" styles={{ body: { padding: 12 } }}>
            <ReactEChartsCore option={trendOption} notMerge style={{ height: 300 }} />
          </Card>
        </Col>
      </Row>

      <Row style={{ marginTop: 16 }}>
        <Col span={24}>
          <Card
            title="系统日志"
            extra={
              <Space>
                <Select
                  placeholder="按类型筛选"
                  style={{ width: 130 }}
                  value={filterType}
                  onChange={value => { setFilterType(value); setPage(1); }}
                  options={[
                    { label: '全部', value: '' },
                    { label: '登录日志', value: 'LOGIN' },
                    { label: '操作日志', value: 'OPERATION' },
                    { label: '系统日志', value: 'SYSTEM' },
                    { label: '错误日志', value: 'ERROR' },
                  ]}
                />
                <Button icon={<ReloadOutlined />} onClick={fetchLogs}>刷新</Button>
              </Space>
            }
          >
            <ResourceStatus title="日志列表" resource={resource} />
            <Table
              loading={resource.loading}
              columns={columns}
              dataSource={logs}
              rowKey="id"
              size="small"
              scroll={{ x: 1300, y: 400 }}
              pagination={{ current: page, pageSize, total: resource.data?.totalElements || 0, onChange: (next, size) => { setPage(size !== pageSize ? 1 : next); setPageSize(size); }, showSizeChanger: true, showTotal: (t) => `共 ${t} 条日志` }}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default SystemLogs;




