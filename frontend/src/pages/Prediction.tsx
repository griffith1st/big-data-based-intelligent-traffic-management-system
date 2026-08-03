import React, { useEffect, useState } from 'react';
import { Card, Row, Col, Table, Tag, Button, Space, Spin, message, Alert, Select, Statistic } from 'antd';
import { ThunderboltOutlined, WarningOutlined } from '@ant-design/icons';
import ReactEChartsCore from 'echarts-for-react';
import { predictionApi } from '../services/api';

const Prediction: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [predicting, setPredicting] = useState(false);
  const [predictions, setPredictions] = useState<any[]>([]);
  const [overview, setOverview] = useState<any[]>([]);
  const [riskData, setRiskData] = useState<any[]>([]);
  const [batchInfo, setBatchInfo] = useState<any>(null);
  const [selectedIntersection, setSelectedIntersection] = useState<string>('');

  const fetchData = async () => {
    try {
      const latestRes = await predictionApi.getLatest();
      const latest = latestRes.code === 200 ? latestRes.data : null;
      const overviewData = latest?.overview || [];
      setBatchInfo(latest);
      setOverview(overviewData);
      setRiskData(latest?.risks || []);

      const targetIntersection = selectedIntersection || overviewData[0]?.intersectionId;
      if (!selectedIntersection && targetIntersection) {
        setSelectedIntersection(targetIntersection);
      }
      if (targetIntersection) {
        const predRes = await predictionApi.getLatest(targetIntersection);
        if (predRes.code === 200) setPredictions(predRes.data?.items || []);
      } else {
        setPredictions([]);
      }
    } catch (error) {
      console.error('获取预测数据失败:', error);
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { fetchData(); }, [selectedIntersection]);

  const handlePredict = async () => {
    setPredicting(true);
    try {
      const res = await predictionApi.triggerLstm();
      if (res.code === 200) {
        message.success('LSTM 预测完成');
        const batch = res.data || {};
        const overviewData = batch.overview || [];
        const targetIntersection = selectedIntersection || overviewData[0]?.intersectionId;
        setBatchInfo(batch);
        setOverview(overviewData);
        setRiskData(batch.risks || []);
        setPredictions(targetIntersection
          ? (batch.items || []).filter((item: any) => item.intersectionId === targetIntersection)
          : []);
        if (!selectedIntersection && targetIntersection) setSelectedIntersection(targetIntersection);
      } else {
        message.error(res.message || '预测失败');
      }
    } catch (error) {
      message.error('预测请求失败');
    } finally {
      setPredicting(false);
    }
  };

  // LSTM 预测结果趋势图
  const predictionChartOption = {
    tooltip: { trigger: 'axis' as const },
    legend: { data: ['预测流量', '预测速度', '置信度'] },
    xAxis: {
      type: 'category' as const,
      data: predictions.map((d) => d.predictionTime ? new Date(d.predictionTime).toLocaleTimeString() : ''),
    },
    yAxis: [
      { type: 'value' as const, name: '流量(辆)' },
      { type: 'value' as const, name: '速度(km/h) / 置信度' },
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
      {
        name: '置信度',
        type: 'line',
        yAxisIndex: 1,
        data: predictions.map((d) => (d.confidence || 0) * 100),
        smooth: true,
        lineStyle: { type: 'dashed' },
        itemStyle: { color: '#fa8c16' },
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
    {
      title: '置信度', dataIndex: 'confidence', key: 'confidence',
      render: (v: number) => <span style={{ color: (v || 0) > 0.8 ? '#52c41a' : '#fa8c16' }}>{((v || 0) * 100).toFixed(1)}%</span>,
    },
    { title: '模型版本', dataIndex: 'modelVersion', key: 'modelVersion' },
  ];

  if (loading) return <div style={{ textAlign: 'center', padding: 100 }}><Spin size="large" /></div>;

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Space size="middle">
          <Select
            placeholder="选择路口"
            style={{ width: 200 }}
            value={selectedIntersection}
            onChange={setSelectedIntersection}
            options={Array.from(new Set(overview.map((d: any) => d.intersectionId)))
              .map((id) => {
                const item = overview.find((d: any) => d.intersectionId === id);
                return { label: item?.intersectionName || id, value: id };
              })}
            allowClear
          />
          <span style={{ color: '#666' }}>
            LSTM 模型: 基于实时/历史交通数据预测未来30分钟{batchInfo?.batchId ? `，当前批次 ${batchInfo.batchId}` : ''}
          </span>
        </Space>
        <Button type="primary" icon={<ThunderboltOutlined />} loading={predicting} onClick={handlePredict}>
          触发 LSTM 预测
        </Button>
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
          <Card title="LSTM 流量预测趋势" bodyStyle={{ padding: 12 }}>
            <ReactEChartsCore option={predictionChartOption} notMerge style={{ height: 350 }} />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="各路口预测概览" bodyStyle={{ padding: 12 }}>
            <ReactEChartsCore option={overviewOption} notMerge style={{ height: 350 }} />
          </Card>
        </Col>
      </Row>

      <Row style={{ marginTop: 16 }}>
        <Col span={24}>
          <Card title="LSTM 预测详情" bodyStyle={{ padding: 0 }}>
            <Table
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




