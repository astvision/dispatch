import { Layout, Menu, Result, Spin, Typography } from "antd";
import LogsPage from "./manage/LogsPage";
import PeoplePage from "./manage/PeoplePage";
import ProjectsPage from "./manage/ProjectsPage";
import SettingsPage from "./manage/SettingsPage";
import OverviewPage from "./OverviewPage";
import SetupPage from "./setup/SetupPage";
import { usePath } from "./usePath";
import { useSetupState } from "./useSetupState";

const PAGES = [
  { key: "/", label: "Overview" },
  { key: "/projects", label: "Projects" },
  { key: "/people", label: "People" },
  { key: "/settings", label: "Settings" },
  { key: "/logs", label: "Logs" },
];

function Page({ path }: { path: string }) {
  switch (path) {
    case "/projects":
      return <ProjectsPage />;
    case "/people":
      return <PeoplePage />;
    case "/settings":
      return <SettingsPage />;
    case "/logs":
      return <LogsPage />;
    default:
      return <OverviewPage />;
  }
}

export default function App() {
  const { state, error, refresh } = useSetupState();
  const [path, navigate] = usePath();

  if (error) return <Result status="warning" title="Cannot reach Dispatch" subTitle={error.message} />;
  if (!state) return <Spin size="large" tip="Loading…"><div style={{ height: 200 }} /></Spin>;

  const page = PAGES.some((candidate) => candidate.key === path) ? path : "/";
  return (
    <Layout style={{ minHeight: "100vh" }}>
      <Layout.Sider breakpoint="md" collapsedWidth={0} theme="light">
        <Typography.Title level={4} style={{ padding: "16px 24px", margin: 0 }}>Dispatch</Typography.Title>
        <Menu mode="inline" selectedKeys={[state.configExists ? page : "setup"]}
              items={state.configExists ? PAGES : [{ key: "setup", label: "Setup" }]}
              onClick={({ key }) => state.configExists && navigate(key)} />
      </Layout.Sider>
      <Layout.Content style={{ padding: 24, maxWidth: 1200 }}>
        {state.configExists ? <Page path={page} /> : <SetupPage onDone={() => { navigate("/"); void refresh(); }} />}
      </Layout.Content>
    </Layout>
  );
}
