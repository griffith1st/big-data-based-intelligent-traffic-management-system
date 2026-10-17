import React, { useState } from 'react';
import { Card, Table, Tag, Row, Col, Statistic, Select, Space, Button, message, Empty } from 'antd';
import { CarOutlined, WarningOutlined, CheckCircleOutlined } from '@ant-design/icons';
import ReactEChartsCore from 'echarts-for-react';
import { trafficApi } from '../services/api';
import { useResource } from '../services/useResource';
import ResourceStatus from '../components/ResourceStatus';
import { useAuth } from '../services/AuthContext';
import { canManageTraffic } from '../services/session';

const TrafficMonitor: React.FC = () => {
  const { user } = useAuth();
  const realtime = useResource(signal => trafficApi.getRealTime(signal).then(r => r.data || []), [], 5000);
  const heatmap = useResource(signal => trafficApi.getHeatmap(signal).then(r => r.data || []), [], 5000);
  const realtimeData = realtime.data || [];
  const heatmapData = heatmap.data || [];
  const [selectedIntersection, setSelectedIntersection] = useState<string>();
  const [generating, setGenerating] = useState(false);
  const generate = async () => {
    setGenerating(true);
    try { await trafficApi.generateOnce(); message.success('已提交一批模拟数据，消费入库后显示'); realtime.reload(); heatmap.reload(); }
    catch (error: any) { message.error(error.message); }
    finally { setGenerating(false); }
  };

  // 各方向车流量饼图
  const directionPieOption = {
    tooltip: { trigger: 'item' as const, formatter: '{b}: {c} 辆 ({d}%)' },
    legend: { bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        center: ['50%', '45%'],
        data: (['NORTH', 'SOUTH', 'EAST', 'WEST'] as const).map((dir) => {
          const count = realtimeData
            .filter((d) => d.direction === dir)
            .reduce((sum: number, d: any) => sum + (d.vehicleCount || 0), 0);
          return { name: ({ NORTH: '北向', SOUTH: '南向', EAST: '东向', WEST: '西向' } as Record<string, string>)[dir], value: count };
        }),
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
        label: { show: true, formatter: '{b}\n{d}%' },
      },
    ],
  };

  const columns = [
    { title: '路口名称', dataIndex: 'intersectionName', key: 'intersectionName', width: 130 },
    { title: '车流量(辆)', dataIndex: 'vehicleCount', key: 'vehicleCount', width: 100, sorter: (a: any, b: any) => a.vehicleCount - b.vehicleCount },
    { title: '平均速度(km/h)', dataIndex: 'avgSpeed', key: 'avgSpeed', width: 110, render: (v: number) => v?.toFixed(1) || '0.0' },
    { title: '占有率', dataIndex: 'occupancyRate', key: 'occupancyRate', width: 90, render: (v: number) => `${(v || 0).toFixed(1)}%` },
    {
      title: '拥堵等级', dataIndex: 'congestionLevel', key: 'congestionLevel', width: 100,
      render: (level: number) => {
        const colors = ['#00b300', '#ffcc00', '#ff6600', '#ff0000'];
        const labels = ['畅通', '轻度拥堵', '中度拥堵', '严重拥堵'];
        return <Tag color={colors[level] || '#999'}>{labels[level] || '未知'}</Tag>;
      },
      filters: [
        { text: '畅通', value: 0 }, { text: '轻度拥堵', value: 1 },
        { text: '中度拥堵', value: 2 }, { text: '严重拥堵', value: 3 },
      ],
      onFilter: (value: any, record: any) => record.congestionLevel === value,
    },
    { title: '方向', dataIndex: 'direction', key: 'direction', width: 70 },
    { title: '数据来源', dataIndex: 'dataSource', key: 'dataSource', width: 90 },
    {
      title: '记录时间', dataIndex: 'recordTime', key: 'recordTime', width: 160,
      render: (v: string) => v ? new Date(v).toLocaleString() : '',
    },
  ];


  const totalVehicles = heatmapData.reduce((s: number, d: any) => s + (d.totalVehicleCount || 0), 0);
  const stats = {
    total: totalVehicles,
    avgSpeed: totalVehicles > 0
      ? (heatmapData.reduce((s: number, d: any) => s + (d.averageSpeed || 0) * (d.totalVehicleCount || 0), 0) / totalVehicles).toFixed(1)
      : 0,
    congested: heatmapData.filter((d: any) => (d.worstCongestionLevelValue || d.congestionLevel || 0) >= 2).length,
    smooth: heatmapData.filter((d: any) => (d.worstCongestionLevelValue || d.congestionLevel || 0) === 0).length,
  };

  return (
    <div>
      <ResourceStatus title="路口汇总" resource={heatmap} />
      {canManageTraffic(user?.role) && <Button onClick={generate} loading={generating} style={{ marginBottom: 16 }}>生成一批模拟交通数据</Button>}
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Card><Statistic title="总车流量" value={heatmap.updatedAt ? stats.total : '—'} prefix={<CarOutlined />} suffix="辆" valueStyle={{ color: '#1677ff' }} /></Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card><Statistic title="平均速度" value={heatmap.updatedAt ? stats.avgSpeed : '—'} prefix={<CarOutlined />} suffix="km/h" valueStyle={{ color: '#52c41a' }} /></Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card><Statistic title="拥堵路口" value={heatmap.updatedAt ? stats.congested : '—'} prefix={<WarningOutlined />} valueStyle={{ color: stats.congested > 0 ? '#ff4d4f' : '#52c41a' }} /></Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card><Statistic title="畅通路口" value={heatmap.updatedAt ? stats.smooth : '—'} prefix={<CheckCircleOutlined />} valueStyle={{ color: '#52c41a' }} /></Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card title="各方向车流量分布" styles={{ body: { padding: 12 } }}>
            {realtimeData.length ? <ReactEChartsCore option={directionPieOption} style={{ height: 350 }} /> : <Empty description="暂无交通采样数据" />}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="路口详细数据" styles={{ body: { padding: 12 } }}>
            <ReactEChartsCore
              option={{
                tooltip: { trigger: 'axis' as const },
                legend: { data: ['车流量', '速度'] },
                xAxis: { type: 'category' as const, data: heatmapData.map((d) => d.intersectionName) },
                yAxis: [
                  { type: 'value' as const, name: '车流量' },
                  { type: 'value' as const, name: '速度(km/h)' },
                ],
                series: [
                  { name: '车流量', type: 'bar', data: heatmapData.map((d) => d.totalVehicleCount ?? d.vehicleCount), itemStyle: { color: '#1677ff' } },
                  { name: '速度', type: 'line', yAxisIndex: 1, data: heatmapData.map((d) => d.averageSpeed ?? d.avgSpeed), itemStyle: { color: '#52c41a' } },
                ],
              }}
              notMerge
              style={{ height: 350 }}
            />
          </Card>
        </Col>
      </Row>

      <Row style={{ marginTop: 16 }}>
        <Col span={24}>
          <Card
            title="实时交通流量明细"
            extra={
              <Space>
                <Select
                  placeholder="选择路口"
                  style={{ width: 200 }}
                  value={selectedIntersection}
                  onChange={setSelectedIntersection}
                  options={Array.from(new Set(realtimeData.map((d: any) => d.intersectionId)))
                    .map((id) => {
                      const item = realtimeData.find((d: any) => d.intersectionId === id);
                      return { label: item?.intersectionName || id, value: id };
                    })}
                  allowClear
                />
              </Space>
            }
          >
            <ResourceStatus title="实时明细" resource={realtime} />
            <Table
              loading={realtime.loading && !realtime.updatedAt}
              columns={columns}
              dataSource={selectedIntersection
                ? realtimeData.filter((d: any) => d.intersectionId === selectedIntersection)
                : realtimeData}
              rowKey={(row: any) => row.id || `${row.intersectionId}-${row.direction}-${row.recordTime}`}
              size="small"
              scroll={{ x: 800, y: 400 }}
              pagination={{ pageSize: 20, showSizeChanger: true, showTotal: (t) => `共 ${t} 条` }}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default TrafficMonitor;

