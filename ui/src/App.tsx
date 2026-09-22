import { Layout, Menu, Typography } from "antd";
import OverviewPage from "./OverviewPage";

export default function App() {
  return (
    <Layout style={{ minHeight: "100vh" }}>
      <Layout.Sider breakpoint="md" collapsedWidth={0} theme="light">
        <Typography.Title level={4} style={{ padding: "16px 24px", margin: 0 }}>Dispatch</Typography.Title>
        <Menu mode="inline" selectedKeys={["overview"]} items={[{ key: "overview", label: "Overview" }]} />
      </Layout.Sider>
      <Layout.Content style={{ padding: 24, maxWidth: 960 }}>
        <OverviewPage />
      </Layout.Content>
    </Layout>
  );
}
