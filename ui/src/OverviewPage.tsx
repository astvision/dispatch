import { CheckCircleTwoTone, CloseCircleTwoTone, ExclamationCircleTwoTone } from "@ant-design/icons";
import { Alert, Button, Card, Descriptions, Empty, List, Result, Space, Spin, Tag, Typography } from "antd";
import type { ReactNode } from "react";
import type { Finding, ServiceView } from "./api";
import { useOverview } from "./useOverview";

const levelIcon: Record<Finding["level"], ReactNode> = {
  OK: <CheckCircleTwoTone twoToneColor="#52c41a" aria-label="ok" />,
  WARN: <ExclamationCircleTwoTone twoToneColor="#faad14" aria-label="warning" />,
  FAIL: <CloseCircleTwoTone twoToneColor="#ff4d4f" aria-label="problem" />,
};

function ServiceTag({ service }: { service: ServiceView }) {
  if (!service.installed) return <Tag>Not installed</Tag>;
  return service.running ? <Tag color="green">Running</Tag> : <Tag color="red">Stopped</Tag>;
}

export default function OverviewPage() {
  const { overview, error, loading, reload } = useOverview();

  if (loading && !overview) {
    return (
      <Spin size="large" tip="Checking Dispatch…">
        <div style={{ height: 200 }} />
      </Spin>
    );
  }
  if (error) {
    return (
      <Result
        status="warning"
        title="Cannot show the overview"
        subTitle={error.message}
        extra={<Button onClick={() => void reload()}>Try again</Button>}
      />
    );
  }
  if (!overview) return <Empty />;

  return (
    <Space direction="vertical" size="large" style={{ width: "100%" }}>
      {!overview.configured && (
        <Alert
          type="info"
          showIcon
          message="Dispatch is not set up yet"
          description="Run dispatch init in a terminal on this machine. Setting up in the browser comes next."
        />
      )}
      <Card title="Dispatch">
        <Descriptions column={1} size="small">
          <Descriptions.Item label="Version">{overview.version}</Descriptions.Item>
          <Descriptions.Item label="Config">
            <Typography.Text code>{overview.configFile}</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="State">
            <Typography.Text code>{overview.stateDir}</Typography.Text>
          </Descriptions.Item>
        </Descriptions>
      </Card>
      <Card title="Background service" extra={<ServiceTag service={overview.service} />}>
        <Typography.Paragraph>
          {overview.service.name}: {overview.service.detail}
        </Typography.Paragraph>
        {overview.service.notes.map((note) => (
          <Alert key={note} type="warning" showIcon message={note} />
        ))}
      </Card>
      <Card title="Checks" extra={<Button loading={loading} onClick={() => void reload()}>Check again</Button>}>
        <List
          locale={{ emptyText: "No checks ran" }}
          dataSource={overview.findings}
          renderItem={(finding) => (
            <List.Item>
              <List.Item.Meta avatar={levelIcon[finding.level]} title={finding.area} description={finding.message} />
            </List.Item>
          )}
        />
      </Card>
    </Space>
  );
}
