import React, { useEffect, useMemo, useState } from 'react';
import { Card, Row, Col, Form, Select, Button, Table, Tag, Space, Spin, message, Statistic } from 'antd';
import { EnvironmentOutlined, SwapOutlined, CarOutlined, SafetyOutlined, ClockCircleOutlined } from '@ant-design/icons';
import ReactEChartsCore from 'echarts-for-react';
import { routeApi, trafficApi } from '../services/api';

const STRATEGY_MAP: Record<string, { label: string; icon: React.ReactNode; desc: string; color: string }> = {
  SHORTEST: { label: '最短路径', icon: <SwapOutlined />, desc: '优先减少行驶距离', color: 'blue' },
  FASTEST: { label: '最快路径', icon: <ClockCircleOutlined />, desc: '优先减少通行时间', color: 'green' },
  GREENEST: { label: '低碳路径', icon: <SafetyOutlined />, desc: '优先降低碳排放', color: 'orange' },
};

const parseJsonArray = (value: unknown): any[] => {
  if (Array.isArray(value)) return value;
  if (typeof value !== 'string' || !value.trim()) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
};

const RoutePlanning: React.FC = () => {
  const [booting, setBooting] = useState(true);
  const [loading, setLoading] = useState(false);
  const [intersections, setIntersections] = useState<any[]>([]);
  const [networkEdges, setNetworkEdges] = useState<any[]>([]);
  const [routeResult, setRouteResult] = useState<any>(null);
  const [history, setHistory] = useState<any[]>([]);
  const [trafficMap, setTrafficMap] = useState<Map<string, number>>(new Map());
  const [trafficTimestamp, setTrafficTimestamp] = useState<string>('');
  const [form] = Form.useForm();

  const getUserId = () => {
    const userInfo = JSON.parse(localStorage.getItem('userInfo') || '{}');
    return userInfo.userId as number | undefined;
  };

  const refreshHistory = async () => {
    const histRes = await routeApi.getHistory(getUserId());
    if (histRes.code === 200) setHistory(histRes.data || []);
  };

  const fetchBaseData = async () => {
    try {
      const [nodesRes, networkRes, historyRes] = await Promise.all([
        routeApi.getIntersections(),
        routeApi.getNetwork(),
        routeApi.getHistory(getUserId()),
      ]);
      const nodes = nodesRes.code === 200 ? nodesRes.data || [] : [];
      setIntersections(nodes);
      setNetworkEdges(networkRes.code === 200 ? networkRes.data?.edges || [] : []);
      setHistory(historyRes.code === 200 ? historyRes.data || [] : []);
      if (nodes.length >= 2) {
        form.setFieldsValue({
          start: nodes[0].intersectionId,
          end: nodes[1].intersectionId,
          strategy: 'SHORTEST',
        });
      }
    } catch (error: any) {
      message.error(error.message || '获取路网失败');
    } finally {
      setBooting(false);
    }
  };

  useEffect(() => { fetchBaseData(); }, []);

  const fetchTraffic = async () => {
    try {
      const res = await trafficApi.getRealTime();
      if (res.code === 200 && res.data) {
        const map = new Map<string, number>();
        (res.data as any[]).forEach((item: any) => {
          const id = item.intersectionId;
          const level = item.congestionLevel ?? 0;
          map.set(id, Math.max(map.get(id) ?? 0, level));
        });
        setTrafficMap(map);
        setTrafficTimestamp(new Date().toLocaleTimeString());
      }
    } catch {
      // traffic data is optional
    }
  };

  useEffect(() => {
    fetchTraffic();
    const timer = setInterval(fetchTraffic, 5000);
    return () => clearInterval(timer);
  }, []);

  const getEdgeCongestionLevel = (from: string, to: string): number => {
    return Math.max(trafficMap.get(from) ?? 0, trafficMap.get(to) ?? 0);
  };

  const CONGESTION_COLORS: Record<number, string> = {
    0: '#52c41a',
    1: '#faad14',
    2: '#fa8c16',
    3: '#ff4d4f',
  };

  const CONGESTION_LABELS: Record<number, string> = {
    0: '畅通',
    1: '轻度拥堵',
    2: '中度拥堵',
    3: '严重拥堵',
  };

  const handlePlan = async (values: any) => {
    if (values.start === values.end) {
      message.warning('起点和终点不能相同');
      return;
    }
    setLoading(true);
    try {
      const res = await routeApi.planByIntersection({
        startIntersectionId: values.start,
        endIntersectionId: values.end,
        strategy: values.strategy,
        userId: getUserId(),
      });
      if (res.code === 200) {
        setRouteResult(res.data);
        message.success('路线规划完成');
        refreshHistory();
      } else {
        message.error(res.message || '路线规划失败');
      }
    } catch (error: any) {
      message.error(error.message || '路线规划请求失败');
    } finally {
      setLoading(false);
    }
  };

  const locationOptions = intersections.map((item) => ({
    label: `${item.intersectionId}: ${item.intersectionName}`,
    value: item.intersectionId,
  }));

  const getRouteChartOption = () => {
    const pathNodes = parseJsonArray(routeResult?.routePoints);
    const pathEdges = parseJsonArray(routeResult?.routeEdges);
    const pathIds = new Set(pathNodes.map((node) => node.intersectionId));
    const startId = routeResult?.startIntersectionId || form.getFieldValue('start');
    const endId = routeResult?.endIntersectionId || form.getFieldValue('end');

    const allPoints = intersections.map((node) => [node.longitude, node.latitude]);
    const xValues = allPoints.map((p) => p[0]);
    const yValues = allPoints.map((p) => p[1]);

    return {
      tooltip: {
        trigger: 'item' as const,
        formatter: (params: any) => {
          const data = params.data || {};
          if (data.edgeInfo) {
            const e = data.edgeInfo;
            const congestion = e.congestionLabel || (e.congestionLevel !== undefined ? CONGESTION_LABELS[e.congestionLevel] : '');
            const congestionHtml = congestion ? `<br/>路况: ${congestion}` : '';
            const timeHtml = e.estimatedTimeMin !== undefined ? `<br/>预计时间: ${e.estimatedTimeMin} 分钟` : '';
            return `${e.from} → ${e.to}<br/>距离: ${e.distanceKm} km${timeHtml}${congestionHtml}`;
          }
          return `<strong>${data.name || ''}</strong><br/>${data.intersectionId || ''}`;
        },
      },
      xAxis: { type: 'value' as const, min: Math.min(...xValues) - 0.015, max: Math.max(...xValues) + 0.015, show: false },
      yAxis: { type: 'value' as const, min: Math.min(...yValues) - 0.015, max: Math.max(...yValues) + 0.015, show: false },
      series: [
        {
          name: '道路网络',
          type: 'lines',
          coordinateSystem: 'cartesian2d',
          data: networkEdges.map((edge) => {
            const from = intersections.find((node) => node.intersectionId === edge.from);
            const to = intersections.find((node) => node.intersectionId === edge.to);
            if (!from || !to) return null;
            const level = getEdgeCongestionLevel(edge.from, edge.to);
            const color = CONGESTION_COLORS[level] || '#d9d9d9';
            return {
              coords: [[from.longitude, from.latitude], [to.longitude, to.latitude]],
              edgeInfo: edge,
              lineStyle: { color, width: Math.max(1.5, level + 1.5), opacity: 0.7 },
            };
          }).filter(Boolean),
          z: 1,
        },
        {
          name: '规划路径',
          type: 'lines',
          coordinateSystem: 'cartesian2d',
          data: pathEdges.map((edge) => {
            const from = intersections.find((node) => node.intersectionId === edge.from);
            const to = intersections.find((node) => node.intersectionId === edge.to);
            if (!from || !to) return null;
            const level = edge.congestionLevel ?? getEdgeCongestionLevel(edge.from, edge.to);
            return {
              coords: [[from.longitude, from.latitude], [to.longitude, to.latitude]],
              edgeInfo: { ...edge, congestionLabel: CONGESTION_LABELS[level] || '' },
              lineStyle: { color: '#1677ff', width: 5, shadowBlur: 8, shadowColor: 'rgba(22,119,255,0.3)' },
            };
          }).filter(Boolean),
          effect: { show: true, period: 4, trailLength: 0.25, symbol: 'arrow', symbolSize: 8 },
          z: 3,
        },
        {
          name: '路口',
          type: 'scatter',
          data: intersections.map((node) => ({
            value: [node.longitude, node.latitude],
            name: node.intersectionName,
            intersectionId: node.intersectionId,
            itemStyle: {
              color: node.intersectionId === startId ? '#52c41a'
                : node.intersectionId === endId ? '#ff4d4f'
                : pathIds.has(node.intersectionId) ? '#1677ff' : '#faad14',
            },
            symbolSize: node.intersectionId === startId || node.intersectionId === endId ? 22 : pathIds.has(node.intersectionId) ? 18 : 14,
          })),
          label: { show: true, formatter: (params: any) => params.data.name, position: 'right', fontSize: 11 },
          z: 4,
        },
      ],
    };
  };

  const historyColumns = [
    { title: '起点', dataIndex: 'startIntersectionName', key: 'startIntersectionName', render: (v: string, r: any) => v || r.startIntersectionId || '-' },
    { title: '终点', dataIndex: 'endIntersectionName', key: 'endIntersectionName', render: (v: string, r: any) => v || r.endIntersectionId || '-' },
    {
      title: '策略', dataIndex: 'strategy', key: 'strategy',
      render: (s: string) => {
        const info = STRATEGY_MAP[s];
        return info ? <Tag icon={info.icon} color={info.color}>{info.label}</Tag> : <Tag>{s}</Tag>;
      },
    },
    { title: '距离(km)', dataIndex: 'totalDistance', key: 'totalDistance', render: (v: number) => v?.toFixed(2) || '-' },
    { title: '预计时间(min)', dataIndex: 'estimatedTime', key: 'estimatedTime', render: (v: number) => v?.toFixed(0) || '-' },
    { title: '碳排放(g)', dataIndex: 'carbonEmission', key: 'carbonEmission', render: (v: number) => v?.toFixed(1) || '-' },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', render: (v: string) => v ? new Date(v).toLocaleString() : '' },
  ];

  const selectedStrategy = Form.useWatch('strategy', form);
  const strategyInfo = useMemo(() => STRATEGY_MAP[selectedStrategy || 'SHORTEST'], [selectedStrategy]);

  const routeCongestionSummary = useMemo(() => {
    const edges = parseJsonArray(routeResult?.routeEdges);
    if (!edges.length) return null;
    const levels: number[] = edges.map((e: any) => e.congestionLevel ?? 0);
    const maxLevel = Math.max(...levels);
    const avgLevel = levels.reduce((a: number, b: number) => a + b, 0) / levels.length;
    return { maxLevel, avgLevel, maxLabel: CONGESTION_LABELS[maxLevel] || '畅通' };
  }, [routeResult]);

  if (booting) return <div style={{ textAlign: 'center', padding: 100 }}><Spin size="large" /></div>;

  return (
    <div>
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={8}>
          <Card title="路线规划" style={{ height: '100%' }}>
            <Form form={form} layout="vertical" onFinish={handlePlan}>
              <Form.Item label="起点" name="start" rules={[{ required: true, message: '请选择起点' }]}>
                <Select options={locationOptions} placeholder="请选择起点" />
              </Form.Item>
              <Form.Item label="终点" name="end" rules={[{ required: true, message: '请选择终点' }]}>
                <Select options={locationOptions} placeholder="请选择终点" />
              </Form.Item>
              <Form.Item label="规划策略" name="strategy" rules={[{ required: true }]}>
                <Select options={Object.entries(STRATEGY_MAP).map(([key, val]) => ({
                  label: <Space>{val.icon}<span>{val.label}</span><span style={{ color: '#999', fontSize: 12 }}>{val.desc}</span></Space>,
                  value: key,
                }))} />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={loading} icon={<EnvironmentOutlined />} block size="large">
                开始规划
              </Button>
            </Form>
            {strategyInfo && <Tag color={strategyInfo.color} style={{ marginTop: 16 }}>{strategyInfo.label}</Tag>}
          </Card>
        </Col>

        <Col xs={24} lg={16}>
          <Card title="路线可视化" bodyStyle={{ padding: 12 }}>
            <ReactEChartsCore option={getRouteChartOption()} notMerge style={{ height: 390 }} />
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 16, marginTop: 8, fontSize: 12, color: '#666' }}>
              {Object.entries(CONGESTION_LABELS).map(([level, label]) => (
                <span key={level} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <span style={{
                    display: 'inline-block', width: 24, height: 4, borderRadius: 2,
                    background: CONGESTION_COLORS[Number(level)] || '#d9d9d9',
                  }} />
                  {label}
                </span>
              ))}
              <span style={{ marginLeft: 8, color: '#999' }}>
                实时路况 {trafficTimestamp ? `更新于 ${trafficTimestamp}` : '加载中...'}
              </span>
            </div>
          </Card>

          {routeResult && (
            <Card title="规划结果" style={{ marginTop: 16 }} bodyStyle={{ padding: 16 }}>
              <Row gutter={[16, 16]}>
                <Col span={6}><Statistic title="总距离" value={routeResult.totalDistance?.toFixed(2) || 0} suffix="km" prefix={<CarOutlined />} valueStyle={{ color: '#1677ff', fontSize: 22 }} /></Col>
                <Col span={6}><Statistic title="预计时间" value={routeResult.estimatedTime?.toFixed(0) || 0} suffix="分钟" prefix={<ClockCircleOutlined />} valueStyle={{ color: '#52c41a', fontSize: 22 }} /></Col>
                <Col span={6}><Statistic title="碳排放" value={routeResult.carbonEmission?.toFixed(1) || 0} suffix="g" prefix={<SafetyOutlined />} valueStyle={{ color: '#fa8c16', fontSize: 22 }} /></Col>
                <Col span={6}>
                  <Statistic
                    title="沿途路况"
                    value={routeCongestionSummary?.maxLabel || '畅通'}
                    prefix={<span style={{
                      display: 'inline-block', width: 12, height: 12, borderRadius: '50%',
                      background: CONGESTION_COLORS[routeCongestionSummary?.maxLevel ?? 0] || '#52c41a',
                      marginRight: 4,
                    }} />}
                    valueStyle={{ color: CONGESTION_COLORS[routeCongestionSummary?.maxLevel ?? 0] || '#52c41a', fontSize: 20 }}
                  />
                </Col>
              </Row>
            </Card>
          )}
        </Col>
      </Row>

      <Row style={{ marginTop: 16 }}>
        <Col span={24}>
          <Card title="历史规划" bodyStyle={{ padding: 0 }}>
            <Table columns={historyColumns} dataSource={history} rowKey="id" size="small" scroll={{ x: 900 }} pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条记录` }} />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default RoutePlanning;
