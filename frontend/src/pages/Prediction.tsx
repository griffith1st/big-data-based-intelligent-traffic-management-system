import React, { useState } from 'react';
import { Card, Row, Col, Table, Tag, Button, Space, message, Alert, Select, Empty } from 'antd';
import { ThunderboltOutlined, WarningOutlined } from '@ant-design/icons';
import ReactEChartsCore from 'echarts-for-react';
import { predictionApi } from '../services/api';
import { useResource } from '../services/useResource';
import ResourceStatus from '../components/ResourceStatus';
import { useAuth } from '../services/AuthContext';
import { canManageTraffic } from '../services/session';

const Prediction: React.FC = () => {
  const { user } = useAuth();
  const resource = useResource(signal => predictionApi.getLatest(undefined, signal).then(r => r.data));
  const [predicting, setPredicting] = useState(false);
  const [selection, setSelection] = useState<string>();
  const batchInfo = resource.data;
  const overview: any[] = batchInfo?.overview || [];
  const riskData: any[] = batchInfo?.risks || [];
  const selectedIntersection = selection || overview[0]?.intersectionId;
  const predictions: any[] = (batchInfo?.items || []).filter((item: any) => item.intersectionId === selectedIntersection);
  const handlePredict = async () => {
    setPredicting(true);
    try { await predictionApi.triggerLstm(); message.success('模拟预测批次已生成'); resource.reload(); }
    catch (error: any) { message.error(error.message || '预测请求失败'); }
    finally { setPredicting(false); }
  };

  // 模拟趋势：仅展示预测量，不把随机分数解释为可靠性。
  const predictionChartOption = {
    tooltip: { trigger: 'axis' as const },
    legend: { data: ['预测流量', '预测速度'] },
    xAxis: {
      type: 'category' as const,
      data: predictions.map((d) => d.predictionTime ? new Date(d.predictionTime).toLocaleTimeString() : ''),
    },
    yAxis: [
      { type: 'value' as const, name: '流量(辆)' },
      { type: 'value' as const, name: '速度(km/h)' },
    ],
    series: [
      {
        name: '预测流量',
        type: 'line',
        data: predictions.map((d) => d.predictedVolume),
        smooth: true,
        lineStyle: { width: 3 },
        itemStyle: { color: '#1677ff' },
        areaStyle: { color: 'rgba(22,119,255,0.1)' },
      },
      {
        name: '预测速度',
        type: 'line',
        yAxisIndex: 1,
        data: predictions.map((d) => d.predictedSpeed),
        smooth: true,
        itemStyle: { color: '#52c41a' },
      },
    ],
  };

  // 预测概览柱状图
  const overviewOption = {
    tooltip: { trigger: 'axis' as const },
    legend: { data: ['预测流量', '预测速度'] },
    xAxis: { type: 'category' as const, data: overview.map((d) => d.intersectionName || d.intersectionId) },
    yAxis: [
      { type: 'value' as const, name: '流量(辆)' },
      { type: 'value' as const, name: '速度(km/h)' },
    ],
    series: [
      {
        name: '预测流量',
        type: 'bar',
        data: overview.map((d) => d.predictedVolume),
        itemStyle: { color: '#1677ff', borderRadius: [4, 4, 0, 0] },
      },
      {
        name: '预测速度',
        type: 'line',
        yAxisIndex: 1,
        data: overview.map((d) => d.predictedSpeed),
        itemStyle: { color: '#52c41a' },
        symbol: 'circle',
        symbolSize: 8,
      },
    ],
  };

  const columns = [
    { title: '预测时间', dataIndex: 'predictionTime', key: 'predictionTime', render: (v: string) => v ? new Date(v).toLocaleString() : '' },
    { title: '预测流量(辆)', dataIndex: 'predictedVolume', key: 'predictedVolume', sorter: (a: any, b: any) => a.predictedVolume - b.predictedVolume },
    { title: '预测速度(km/h)', dataIndex: 'predictedSpeed', key: 'predictedSpeed', render: (v: number) => v?.toFixed(1) || '0.0' },
    {
      title: '预测拥堵等级', dataIndex: 'predictedCongestion', key: 'predictedCongestion',
      render: (level: number) => {
        const colors = ['#00b300', '#ffcc00', '#ff6600', '#ff0000'];
        const labels = ['畅通', '轻度拥堵', '中度拥堵', '严重拥堵'];
        return <Tag color={colors[level] || '#999'}>{labels[level] || '未知'}</Tag>;
      },
    },
    { title: '模型版本', dataIndex: 'modelVersion', key: 'modelVersion' },
  ];


  return (
    <div>
      <Alert type="info" showIcon message="模拟预测" description="基于交通采样趋势计算未来流量，用于演示与方案比较。结果尚无真实预测准确率验证，请勿用于真实调度决策。" style={{ marginBottom: 16 }} />
      <ResourceStatus resource={resource} />
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Space size="middle">
          <Select
            placeholder="选择路口"
            style={{ width: 200 }}
            value={selectedIntersection}
            onChange={setSelection}
            options={Array.from(new Set(overview.map((d: any) => d.intersectionId)))
              .map((id) => {
                const item = overview.find((d: any) => d.intersectionId === id);
                return { label: item?.intersectionName || id, value: id };
              })}
          />
          <span style={{ color: '#666' }}>
            模拟预测：未来30分钟{batchInfo?.batchId ? `，当前批次 ${batchInfo.batchId}` : ''}
          </span>
        </Space>
        {canManageTraffic(user?.role) && <Button type="primary" icon={<ThunderboltOutlined />} loading={predicting} onClick={handlePredict}>生成预测批次</Button>}
      </div>

      {riskData.length > 0 && (
        <Alert
          type="warning"
          showIcon
          icon={<WarningOutlined />}
          message={
            <span>
              检测到 <strong>{riskData.length}</strong> 个路口存在拥堵风险！
              {riskData.slice(0, 3).map((r, i) => (
                <Tag key={i} color="warning" style={{ marginLeft: 8 }}>
                  {r.intersectionName || r.intersectionId}
                </Tag>
              ))}
            </span>
          }
          style={{ marginBottom: 16 }}
        />
      )}

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card title="模拟流量预测趋势" styles={{ body: { padding: 12 } }}>
            {predictions.length ? <ReactEChartsCore option={predictionChartOption} notMerge style={{ height: 350 }} /> : <Empty description="暂无预测数据，交通管理员可生成预测批次" />}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="各路口预测概览" styles={{ body: { padding: 12 } }}>
            {overview.length ? <ReactEChartsCore option={overviewOption} notMerge style={{ height: 350 }} /> : <Empty description="暂无预测概览" />}
          </Card>
        </Col>
      </Row>

      <Row style={{ marginTop: 16 }}>
        <Col span={24}>
          <Card title="模拟预测详情" styles={{ body: { padding: 0 } }}>
            <Table
              loading={resource.loading}
              columns={columns}
              dataSource={predictions}
              rowKey={(row: any) => row.id || `${row.batchId}-${row.intersectionId}-${row.predictionTime}`}
              size="small"
              scroll={{ x: 800, y: 350 }}
              pagination={{ pageSize: 15, showTotal: (t) => `共 ${t} 条预测` }}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default Prediction;




