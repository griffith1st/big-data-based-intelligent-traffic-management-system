import React from 'react';
import { Alert, Button, Space, Typography } from 'antd';

const ResourceStatus: React.FC<{ title?: string; resource: { loading: boolean; error: string; updatedAt: Date | null; reload: () => void } }> = ({ title, resource }) => <div style={{ marginBottom: 12 }}>
  {resource.error && <Alert type="warning" showIcon message={`${title ? `${title}：` : ''}${resource.error}`} description={resource.updatedAt ? '正在显示上次成功获取的数据。' : '数据尚未成功加载。'} style={{ marginBottom: 8 }} />}
  <Space wrap>
    <Typography.Text type="secondary">{title && `${title} · `}{resource.updatedAt ? `最后成功获取：${resource.updatedAt.toLocaleString()}` : resource.loading ? '正在加载...' : '尚无成功获取记录'}</Typography.Text>
    <Button size="small" loading={resource.loading} onClick={resource.reload}>刷新</Button>
  </Space>
</div>;
export default ResourceStatus;
