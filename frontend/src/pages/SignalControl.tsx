import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Card, Row, Col, Table, Tag, Button, Space, Spin, message, Select, Statistic, Descriptions } from 'antd';
import { ThunderboltOutlined, CheckCircleOutlined, SyncOutlined, ClockCircleOutlined } from '@ant-design/icons';
import ReactEChartsCore from 'echarts-for-react';
import { signalApi } from '../services/api';

const STRATEGIES: Record<string, { label: string; color: string }> = {
  BALANCED: { label: '均衡策略', color: 'blue' },
  CONGESTION_FIRST: { label: '拥堵优先', color: 'orange' },
  EMERGENCY_FIRST: { label: '应急优先', color: 'red' },
};

const directionLabel: Record<string, string> = {
  NORTH: '北向',
  SOUTH: '南向',
  EAST: '东向',
  WEST: '西向',
};

const SignalControl: React.FC = () => {
  const [loading, setLoading] = useState(true);
  const [optimizing, setOptimizing] = useState(false);
  const [intersections, setIntersections] = useState<any[]>([]);
  const [signals, setSignals] = useState<any[]>([]);
  const [directionStates, setDirectionStates] = useState<any[]>([]);
  const [selectedIntersection, setSelectedIntersection] = useState<string>('');
  const [strategy, setStrategy] = useState('BALANCED');
  const [optimizationResult, setOptimizationResult] = useState<any>(null);

  const fetchBase = async () => {
    try {
      const res = await signalApi.getIntersections();
      const nodes = res.code === 200 ? res.data || [] : [];
      setIntersections(nodes);
      const target = selectedIntersection || nodes[0]?.intersectionId;
      if (!selectedIntersection && target) setSelectedIntersection(target);
      if (target) await fetchCurrent(target);
    } catch (error) {
      console.error('获取信号灯数据失败:', error);
    } finally {
      setLoading(false);
    }
  };

  const fetchCurrent = async (intersectionId: string) => {
    const res = await signalApi.getCurrent(intersectionId);
    if (res.code === 200) {
      setSignals(res.data?.signals || []);
      setDirectionStates(res.data?.directionStates || []);
    }
  };

  useEffect(() => { fetchBase(); }, []);

  useEffect(() => {
    if (selectedIntersection) {
      fetchCurrent(selectedIntersection);
      setOptimizationResult(null);
    }
  }, [selectedIntersection]);

  const handleOptimize = async () => {
    if (!selectedIntersection) {
      message.warning('请先选择路口');
      return;
    }
    setOptimizing(true);
    try {
      const res = await signalApi.optimize(selectedIntersection, strategy);
      if (res.code === 200) {
        setOptimizationResult(res.data);
        setSignals(res.data?.signals || []);
        setDirectionStates(res.data?.directionStates || []);
        message.success('DQN 信号灯优化完成');
      } else {
        message.error(res.message || '优化失败');
      }
    } catch (error) {
      message.error('优化请求失败');
    } finally {
      setOptimizing(false);
    }
  };

  const handleApply = async () => {
    if (!selectedIntersection) return;
    try {
      const res = await signalApi.applyPlan(selectedIntersection);
      if (res.code === 200) {
        message.success('信号灯方案已应用');
        fetchCurrent(selectedIntersection);
      }
    } catch (error) {
      message.error('应用请求失败');
    }
  };

  const signalGanttOption = {
    tooltip: {
      trigger: 'item' as const,
      formatter: (params: any) => `${params.name}<br/>${params.seriesName}: ${params.value}s`,
    },
    title: {
      text: `周期总时长 ${signals[0]?.cycleDuration || 0}s`,
      right: 8,
      top: 0,
      textStyle: { fontSize: 12, fontWeight: 400, color: '#666' },
    },
    legend: { data: ['绿灯', '黄灯', '红灯'], bottom: 0 },
    grid: { left: 70, right: 28, top: 34, bottom: 48 },
    xAxis: { type: 'value' as const, name: '秒', max: Math.max(100, signals[0]?.cycleDuration || 100) },
    yAxis: {
      type: 'category' as const,
      data: signals.map((s) => directionLabel[s.direction] || s.direction),
      axisLabel: { fontSize: 12 },
    },
    series: [
      {
        name: '绿灯',
        type: 'bar',
        data: signals.map((s) => ({ value: s.greenDuration || 0, name: directionLabel[s.direction] || s.direction })),
        stack: 'total',
        barWidth: 22,
        itemStyle: { color: '#52c41a' },
        label: { show: true, position: 'inside', formatter: (p: any) => `${p.value}s` },
      },
      {
        name: '黄灯',
        type: 'bar',
        data: signals.map((s) => ({ value: s.yellowDuration || 0, name: directionLabel[s.direction] || s.direction })),
        stack: 'total',
        itemStyle: { color: '#faad14' },
      },
      {
        name: '红灯',
        type: 'bar',
        data: signals.map((s) => ({ value: s.redDuration || 0, name: directionLabel[s.direction] || s.direction })),
        stack: 'total',
        itemStyle: { color: '#ff4d4f' },
      },
    ],
  };

  const stateColumns = [
    { title: '方向', dataIndex: 'direction', key: 'direction', render: (v: string) => directionLabel[v] || v },
    { title: '车流量', dataIndex: 'vehicleCount', key: 'vehicleCount' },
    { title: '平均速度', dataIndex: 'averageSpeed', key: 'averageSpeed', render: (v: number) => `${v?.toFixed?.(1) || v} km/h` },
    { title: '排队长度', dataIndex: 'queueLength', key: 'queueLength' },
    { title: '等待时间', dataIndex: 'waitingTime', key: 'waitingTime', render: (v: number) => `${v}s` },
    { title: '拥堵等级', dataIndex: 'congestionLevel', key: 'congestionLevel', render: (v: string) => <Tag color={v === 'HEAVY' ? 'red' : v === 'MEDIUM' ? 'orange' : v === 'LIGHT' ? 'gold' : 'green'}>{v}</Tag> },
  ];

  const signalColumns = [
    { title: '方向', dataIndex: 'direction', key: 'direction', render: (v: string) => directionLabel[v] || v },
    { title: '相位', dataIndex: 'phaseId', key: 'phaseId', render: (v: number) => v === 1 ? 'NS_PHASE' : 'EW_PHASE' },
    { title: '绿灯(s)', dataIndex: 'greenDuration', key: 'greenDuration' },
    { title: '红灯(s)', dataIndex: 'redDuration', key: 'redDuration' },
    { title: '黄灯(s)', dataIndex: 'yellowDuration', key: 'yellowDuration' },
    { title: '周期(s)', dataIndex: 'cycleDuration', key: 'cycleDuration' },
    { title: '策略', dataIndex: 'strategy', key: 'strategy', render: (s: string) => <Tag color={STRATEGIES[s]?.color || 'blue'}>{STRATEGIES[s]?.label || s}</Tag> },
  ];

  const selectedName = useMemo(() => intersections.find((item) => item.intersectionId === selectedIntersection)?.intersectionName || selectedIntersection, [intersections, selectedIntersection]);
  const optimizedCount = signals.filter((s: any) => s.optimizationStatus === 'OPTIMIZED' || s.optimizationStatus === 'APPLIED').length;
  const avgCycle = signals.length ? signals.reduce((sum, item) => sum + (item.cycleDuration || 0), 0) / signals.length : 0;

  if (loading) return <div style={{ textAlign: 'center', padding: 100 }}><Spin size="large" /></div>;

  return (
    <div>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="路口总数" value={intersections.length} prefix={<ClockCircleOutlined />} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="当前路口已优化方向" value={optimizedCount} suffix={`/ ${signals.length}`} prefix={<CheckCircleOutlined />} valueStyle={{ color: '#52c41a' }} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="预计等待降低" value={optimizationResult?.improvementRate ?? 0} suffix="%" prefix={<SyncOutlined />} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="平均周期时长" value={avgCycle.toFixed(0)} suffix="s" /></Card></Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card title="信号灯配时甘特图" bodyStyle={{ padding: 12 }}>
            <ReactEChartsCore option={signalGanttOption} notMerge style={{ height: 350 }} />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="DQN 优化控制台" bodyStyle={{ padding: 24 }}>
            <Descriptions column={1} size="small" style={{ marginBottom: 16 }}>
              <Descriptions.Item label="当前路口">
                <Select
                  style={{ width: 280 }}
                  value={selectedIntersection}
                  onChange={setSelectedIntersection}
                  options={intersections.map((item: any) => ({ label: `${item.intersectionId}: ${item.intersectionName}`, value: item.intersectionId }))}
                />
              </Descriptions.Item>
              <Descriptions.Item label="优化策略">
                <Select
                  style={{ width: 220 }}
                  value={strategy}
                  onChange={setStrategy}
                  options={Object.entries(STRATEGIES).map(([value, item]) => ({ value, label: item.label }))}
                />
              </Descriptions.Item>
              <Descriptions.Item label="路口名称">{selectedName}</Descriptions.Item>
              <Descriptions.Item label="相位联动"><Tag color="green">NS_PHASE</Tag><Tag color="orange">EW_PHASE</Tag></Descriptions.Item>
            </Descriptions>
            <Space>
              <Button type="primary" icon={<ThunderboltOutlined />} loading={optimizing} onClick={handleOptimize}>DQN 优化配时</Button>
              <Button icon={<CheckCircleOutlined />} onClick={handleApply}>应用方案</Button>
            </Space>
            {optimizationResult?.explanation && (
              <Alert type="success" showIcon style={{ marginTop: 16 }} message={optimizationResult.explanation} />
            )}
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card title="方向交通状态" bodyStyle={{ padding: 0 }}>
            <Table columns={stateColumns} dataSource={directionStates} rowKey="direction" size="small" pagination={false} />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="信号灯配时详情" bodyStyle={{ padding: 0 }}>
            <Table columns={signalColumns} dataSource={signals} rowKey={(row: any) => row.id || `${row.intersectionId}-${row.direction}`} size="small" pagination={false} />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default SignalControl;
