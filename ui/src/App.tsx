import { Layout, Menu, Result, Spin, Typography } from "antd";
import OverviewPage from "./OverviewPage";
import SetupPage from "./setup/SetupPage";
import { useSetupState } from "./useSetupState";

export default function App() {
  const { state, error, refresh } = useSetupState();

  if (error) return <Result status="warning" title="Cannot reach Dispatch" subTitle={error.message} />;
  if (!state) return <Spin size="large" tip="Loading…"><div style={{ height: 200 }} /></Spin>;

  return (
    <Layout style={{ minHeight: "100vh" }}>
      <Layout.Sider breakpoint="md" collapsedWidth={0} theme="light">
        <Typography.Title level={4} style={{ padding: "16px 24px", margin: 0 }}>Dispatch</Typography.Title>
        <Menu mode="inline" selectedKeys={[state.configExists ? "overview" : "setup"]}
              items={[{ key: state.configExists ? "overview" : "setup", label: state.configExists ? "Overview" : "Setup" }]} />
      </Layout.Sider>
      <Layout.Content style={{ padding: 24, maxWidth: 960 }}>
        {state.configExists ? <OverviewPage /> : <SetupPage onDone={() => void refresh()} />}
      </Layout.Content>
    </Layout>
  );
}
