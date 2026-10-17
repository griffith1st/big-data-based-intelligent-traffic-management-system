import React, { useMemo, useState } from 'react';
import { Alert, Card, Row, Col, Table, Tag, Button, Space, Empty, message, Select, Statistic, Descriptions } from 'antd';
import { ThunderboltOutlined, CheckCircleOutlined, SyncOutlined, ClockCircleOutlined } from '@ant-design/icons';
import ReactEChartsCore from 'echarts-for-react';
import { signalApi } from '../services/api';
import { useResource } from '../services/useResource';
import ResourceStatus from '../components/ResourceStatus';

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
  const nodes = useResource(signal => signalApi.getIntersections(signal).then(r => r.data || []));
  const intersections = nodes.data || [];
  const [selection, setSelection] = useState<string>();
  const selectedIntersection = selection || intersections[0]?.intersectionId || '';
  const current = useResource(signal => selectedIntersection ? signalApi.getCurrent(selectedIntersection, signal).then(r => r.data) : Promise.resolve(null), [selectedIntersection]);
  const signals: any[] = current.data?.signals || [];
  const directionStates: any[] = current.data?.directionStates || [];
  const [optimizing, setOptimizing] = useState(false);
  const [applying, setApplying] = useState(false);
  const [strategy, setStrategy] = useState('BALANCED');
  const [optimizationResult, setOptimizationResult] = useState<any>(null);
  const candidateSignals: any[] = current.data?.candidateSignals || [];
  const handleOptimize = async () => {
    if (!selectedIntersection) return;
    setOptimizing(true);
    try {
      const res = await signalApi.optimize(selectedIntersection, strategy);
      setOptimizationResult(res.data);
      message.success('规则配时已生成候选方案，应用后更新模拟状态');
      current.reload();
    } catch (error: any) { message.error(error.message || '优化失败'); }
    finally { setOptimizing(false); }
  };
  const handleApply = async () => {
    if (!selectedIntersection || !candidateSignals.length) return;
    setApplying(true);
    try { await signalApi.applyPlan(selectedIntersection); message.success('候选配时已应用到模拟状态'); setOptimizationResult(null); current.reload(); }
    catch (error: any) { message.error(error.message || '应用失败'); }
    finally { setApplying(false); }
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
    { title: '输入来源', dataIndex: 'observed', key: 'observed', render: (observed: boolean) => <Tag color={observed ? 'blue' : 'default'}>{observed ? '近期采样' : '静态估算'}</Tag> },
    { title: '采样时间', dataIndex: 'recordTime', key: 'recordTime', render: (value: string) => value ? new Date(value).toLocaleString() : '—' },
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


  return (
    <div>
      <Alert type="info" showIcon message="规则配时模拟" description="根据流量、排队与等待情况计算候选配时；应用仅更新系统中的模拟方案，尚未接入真实信号控制设备。下图展示各灯色时长占比，不表示道路相位的实际时间轴。" style={{ marginBottom: 16 }} />
      <ResourceStatus title="路口列表" resource={nodes} />
      <ResourceStatus title="当前配时" resource={current} />
      {current.data?.planValid === false && <Alert type="warning" showIcon message="当前历史配时需要重新计算" description={current.data.validationMessage || '请计算并应用新的候选方案。'} style={{ marginBottom: 16 }} />}
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="路口总数" value={intersections.length} prefix={<ClockCircleOutlined />} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="当前路口已优化方向" value={optimizedCount} suffix={`/ ${signals.length}`} prefix={<CheckCircleOutlined />} valueStyle={{ color: '#52c41a' }} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="规则估算等待改善（未实测）" value={optimizationResult?.improvementRate ?? "—"} suffix="%" prefix={<SyncOutlined />} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="平均周期时长" value={avgCycle.toFixed(0)} suffix="s" /></Card></Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card title="当前方案各灯色时长分布" styles={{ body: { padding: 12 } }}>
            {signals.length ? <ReactEChartsCore option={signalGanttOption} notMerge style={{ height: 350 }} /> : <Empty description="暂无当前配时" />}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="规则配时控制台" styles={{ body: { padding: 24 } }}>
            <Descriptions column={1} size="small" style={{ marginBottom: 16 }}>
              <Descriptions.Item label="当前路口">
                <Select
                  style={{ width: 280 }}
                  value={selectedIntersection}
                  disabled={optimizing || applying}
                  onChange={value => { setSelection(value); setOptimizationResult(null); }}
                  options={intersections.map((item: any) => ({ label: `${item.intersectionId}: ${item.intersectionName}`, value: item.intersectionId }))}
                />
              </Descriptions.Item>
              <Descriptions.Item label="优化策略">
                <Select
                  style={{ width: 220 }}
                  value={strategy}
                  onChange={setStrategy}
                  disabled={optimizing || applying}
                  options={Object.entries(STRATEGIES).map(([value, item]) => ({ value, label: value === 'EMERGENCY_FIRST' ? '应急优先（待接入应急数据）' : item.label, disabled: value === 'EMERGENCY_FIRST' }))}
                />
              </Descriptions.Item>
              <Descriptions.Item label="路口名称">{selectedName}</Descriptions.Item>
              <Descriptions.Item label="相位联动"><Tag color="green">NS_PHASE</Tag><Tag color="orange">EW_PHASE</Tag></Descriptions.Item>
            </Descriptions>
            <Space>
              <Button type="primary" icon={<ThunderboltOutlined />} loading={optimizing} disabled={!selectedIntersection || applying || current.loading} onClick={handleOptimize}>计算候选配时</Button>
              <Button icon={<CheckCircleOutlined />} loading={applying} disabled={!candidateSignals.length || optimizing || current.loading} onClick={handleApply}>应用候选方案</Button>
            </Space>
            {optimizationResult?.explanation && (
              <Alert type="success" showIcon style={{ marginTop: 16 }} message={optimizationResult.explanation} />
            )}
          </Card>
        </Col>
      </Row>

      <Card title="待应用候选配时" style={{ marginTop: 16 }}><Table columns={signalColumns} dataSource={candidateSignals} rowKey="direction" size="small" pagination={false} locale={{ emptyText: "尚无候选方案，请先计算候选配时" }} /></Card>
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card title="方向计算输入（缺失时使用静态估算）" styles={{ body: { padding: 0 } }}>
            <Table columns={stateColumns} dataSource={directionStates} rowKey="direction" size="small" pagination={false} scroll={{ x: 750 }} />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="信号灯配时详情" styles={{ body: { padding: 0 } }}>
            <Table columns={signalColumns} dataSource={signals} rowKey={(row: any) => row.id || `${row.intersectionId}-${row.direction}`} size="small" pagination={false} />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default SignalControl;
