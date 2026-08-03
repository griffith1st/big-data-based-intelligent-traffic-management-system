import React, { useEffect, useRef, useState } from 'react';
import { Card, Col, Empty, Row, Spin, Statistic, Table, Tag } from 'antd';
import { CarOutlined, ClockCircleOutlined, WarningOutlined } from '@ant-design/icons';
import ReactEChartsCore from 'echarts-for-react';
import { alertApi, predictionApi, trafficApi } from '../services/api';

const levelLabels = ['畅通', '轻度拥堵', '中度拥堵', '严重拥堵'];
const levelColors = ['#2fb344', '#fadb14', '#fa8c16', '#ff4d4f'];

const Dashboard: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [realtimeData, setRealtimeData] = useState<any[]>([]);
  const [heatmapData, setHeatmapData] = useState<any[]>([]);
  const [predictions, setPredictions] = useState<any[]>([]);
  const [alertCount, setAlertCount] = useState(0);
  const intervalRef = useRef<ReturnType<typeof setInterval>>();

  const fetchData = async () => {
    try {
      const [realtimeRes, heatmapRes, predRes, alertRes] = await Promise.all([
        trafficApi.getRealTime(),
        trafficApi.getHeatmap(),
        predictionApi.getOverview(),
        alertApi.getUnreadCount(),
      ]);
      if (realtimeRes.code === 200) setRealtimeData(realtimeRes.data || []);
      if (heatmapRes.code === 200) setHeatmapData(heatmapRes.data || []);
      if (predRes.code === 200) setPredictions(predRes.data || []);
      if (alertRes.code === 200) setAlertCount(alertRes.data || 0);
    } catch (error) {
      console.error('获取数据失败:', error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
    intervalRef.current = setInterval(fetchData, 5000);
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, []);

  const lngs = heatmapData.map((item) => item.longitude);
  const lats = heatmapData.map((item) => item.latitude);
  const lngPadding = Math.max(0.01, (Math.max(...lngs, 116.5) - Math.min(...lngs, 116.3)) * 0.12);
  const latPadding = Math.max(0.01, (Math.max(...lats, 40.0) - Math.min(...lats, 39.9)) * 0.12);
  const maxFlow = Math.max(...heatmapData.map((item) => item.totalVehicleCount || 0), 1);
  const topCongested = [...heatmapData]
    .sort((a, b) => (b.worstCongestionLevelValue - a.worstCongestionLevelValue) || ((b.totalVehicleCount || 0) - (a.totalVehicleCount || 0)))
    .slice(0, 5);

  const heatmapOption = {
    tooltip: {
      trigger: 'item' as const,
      formatter: (params: any) => {
        const data = params.data || {};
        const details = (data.directionDetails || []).map((d: any) =>
          `${d.direction}: ${d.vehicleCount}辆 / ${Number(d.averageSpeed || 0).toFixed(1)}km/h / ${levelLabels[d.congestionLevelValue] || d.congestionLevel}`
        ).join('<br/>');
        const concern = data.worstCongestionLevelValue >= 2 ? '<br/><strong style="color:#ff4d4f">需要关注</strong>' : '';
        return `<strong>${data.intersectionName}</strong><br/>
          编号: ${data.intersectionId}<br/>
          经纬度: ${data.value[0].toFixed(3)}, ${data.value[1].toFixed(3)}<br/>
          总车流量: ${data.totalVehicleCount} 辆<br/>
          平均速度: ${Number(data.averageSpeed || 0).toFixed(1)} km/h<br/>
          最严重拥堵: ${levelLabels[data.worstCongestionLevelValue] || data.worstCongestionLevel}<br/>
          ${details}<br/>
          时间: ${data.timestamp ? new Date(data.timestamp).toLocaleTimeString() : '-'}${concern}`;
      },
    },
    legend: {
      right: 12,
      bottom: 8,
      orient: 'vertical' as const,
      data: levelLabels,
    },
    grid: { left: 20, right: 95, top: 20, bottom: 35 },
    dataZoom: [{ type: 'inside' as const }, { type: 'slider' as const, height: 14, bottom: 5 }],
    xAxis: {
      type: 'value' as const,
      min: Math.min(...lngs, 116.3) - lngPadding,
      max: Math.max(...lngs, 116.5) + lngPadding,
      show: false,
    },
    yAxis: {
      type: 'value' as const,
      min: Math.min(...lats, 39.9) - latPadding,
      max: Math.max(...lats, 40.0) + latPadding,
      show: false,
    },
    series: [
      ...levelLabels.map((label, level) => ({
        name: label,
        type: 'scatter',
        data: heatmapData.filter((item) => item.worstCongestionLevelValue === level).map((item) => ({
          ...item,
          value: [item.longitude, item.latitude, item.worstCongestionLevelValue],
        })),
        symbolSize: (val: number[], params: any) => 10 + Math.min(30, ((params.data.totalVehicleCount || 0) / maxFlow) * 30),
        itemStyle: {
          color: levelColors[level],
          opacity: 0.72 + level * 0.07,
          borderColor: level >= 2 ? '#7a1f1f' : '#fff',
          borderWidth: level >= 2 ? 3 : 1,
          shadowBlur: level >= 2 ? 16 : 6,
          shadowColor: levelColors[level],
        },
        label: {
          show: level >= 2,
          formatter: (params: any) => `${params.data.intersectionName}\n${label}`,
          position: 'right',
          fontSize: 10,
          color: '#333',
        },
        labelLayout: { hideOverlap: true },
      })),
      {
        name: '重点关注',
        type: 'effectScatter',
        data: heatmapData.filter((item) => item.worstCongestionLevelValue >= 2).map((item) => ({
          ...item,
          value: [item.longitude, item.latitude, item.worstCongestionLevelValue],
        })),
        symbolSize: 28,
        rippleEffect: { scale: 2.8, brushType: 'stroke' },
        itemStyle: { color: '#ff4d4f' },
        z: 5,
      },
    ],
  };

  const trafficTrendOption = {
    tooltip: {
      trigger: 'axis' as const,
      formatter: (params: any[]) => {
        const data = heatmapData[params[0]?.dataIndex] || {};
        const details = (data.directionDetails || []).map((d: any) => `${d.direction}: ${d.vehicleCount}辆, ${d.averageSpeed}km/h`).join('<br/>');
        return `<strong>${data.intersectionName}</strong><br/>总车流量: ${data.totalVehicleCount}辆<br/>平均速度: ${data.averageSpeed}km/h<br/>${details}`;
      },
    },
    legend: { data: ['总车流量', '加权平均速度'] },
    xAxis: { type: 'category' as const, data: heatmapData.map((item) => item.intersectionName), axisLabel: { interval: 0, rotate: 25 } },
    yAxis: [{ type: 'value' as const, name: '车流量(辆)' }, { type: 'value' as const, name: '速度(km/h)' }],
    series: [
      { name: '总车流量', type: 'bar', data: heatmapData.map((item) => item.totalVehicleCount), itemStyle: { color: '#1677ff' } },
      { name: '加权平均速度', type: 'line', yAxisIndex: 1, data: heatmapData.map((item) => item.averageSpeed), itemStyle: { color: '#52c41a' } },
    ],
  };

  const predictionOption = {
    tooltip: { trigger: 'axis' as const },
    legend: { data: ['预测流量', '预测速度'] },
    xAxis: { type: 'category' as const, data: predictions.map((item) => item.intersectionName || item.intersectionId) },
    yAxis: [{ type: 'value' as const, name: '预测流量(辆)' }, { type: 'value' as const, name: '预测速度(km/h)' }],
    series: [
      { name: '预测流量', type: 'bar', data: predictions.map((item) => item.predictedVolume || item.predictedFlow), itemStyle: { color: '#fa8c16' } },
      { name: '预测速度', type: 'line', yAxisIndex: 1, data: predictions.map((item) => item.predictedSpeed), itemStyle: { color: '#1677ff' } },
    ],
  };

  const columns = [
    { title: '路口名称', dataIndex: 'intersectionName', key: 'intersectionName' },
    { title: '车流量(辆)', dataIndex: 'vehicleCount', key: 'vehicleCount' },
    { title: '平均速度(km/h)', dataIndex: 'avgSpeed', key: 'avgSpeed', render: (val: number) => `${val?.toFixed(1) || 0}` },
    {
      title: '拥堵等级', dataIndex: 'congestionLevel', key: 'congestionLevel',
      render: (level: number) => <Tag color={levelColors[level] || '#999'}>{levelLabels[level] || '未知'}</Tag>,
    },
    { title: '方向', dataIndex: 'direction', key: 'direction' },
    { title: '数据来源', dataIndex: 'dataSource', key: 'dataSource' },
    { title: '记录时间', dataIndex: 'recordTime', key: 'recordTime', render: (val: string) => val ? new Date(val).toLocaleTimeString() : '' },
  ];

  const totalVehicles = heatmapData.reduce((sum, item) => sum + (item.totalVehicleCount || 0), 0);
  const avgSpeedAll = totalVehicles > 0
    ? (heatmapData.reduce((sum, item) => sum + (item.averageSpeed || 0) * (item.totalVehicleCount || 0), 0) / totalVehicles).toFixed(1)
    : 0;
  const congestedCount = heatmapData.filter((item) => (item.worstCongestionLevelValue || 0) >= 2).length;

  if (loading) {
    return <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '60vh' }}><Spin size="large" tip="正在加载数据..." /></div>;
  }

  return (
    <div>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}><Card hoverable><Statistic title="实时车流量" value={totalVehicles} prefix={<CarOutlined />} suffix="辆" valueStyle={{ color: '#1677ff' }} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card hoverable><Statistic title="平均速度" value={avgSpeedAll} prefix={<ClockCircleOutlined />} suffix="km/h" valueStyle={{ color: '#52c41a' }} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card hoverable><Statistic title="拥堵路口" value={congestedCount} prefix={<WarningOutlined />} suffix="个" valueStyle={{ color: congestedCount > 0 ? '#ff4d4f' : '#52c41a' }} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card hoverable><Statistic title="未处理告警" value={alertCount} prefix={<WarningOutlined />} valueStyle={{ color: alertCount > 0 ? '#ff4d4f' : '#52c41a' }} /></Card></Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={16}>
          <Card title="路口拥堵热力图" bodyStyle={{ padding: 12 }}>
            <ReactEChartsCore option={heatmapOption} notMerge style={{ height: 390 }} />
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card title="Top 5 拥堵路口" bodyStyle={{ padding: 12 }}>
            {topCongested.map((item) => (
              <div key={item.intersectionId} style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
                <span>{item.intersectionName}</span>
                <Tag color={levelColors[item.worstCongestionLevelValue]}>{levelLabels[item.worstCongestionLevelValue]}</Tag>
              </div>
            ))}
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}><Card title="各路口车流量与速度" bodyStyle={{ padding: 12 }}><ReactEChartsCore option={trafficTrendOption} notMerge style={{ height: 350 }} /></Card></Col>
        <Col xs={24} lg={12}>
          <Card title="LSTM 流量预测概览" bodyStyle={{ padding: 12 }}>
            {predictions.length ? <ReactEChartsCore option={predictionOption} notMerge style={{ height: 350 }} /> : <Empty description="暂无预测数据，请先触发预测" style={{ height: 350, paddingTop: 100 }} />}
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24}>
          <Card title="实时交通数据" bodyStyle={{ padding: 0 }}>
            <Table columns={columns} dataSource={realtimeData.slice(0, 12)} rowKey={(row: any) => row.id || `${row.intersectionId}-${row.direction}-${row.recordTime}`} size="small" pagination={false} scroll={{ y: 300 }} />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default Dashboard;
