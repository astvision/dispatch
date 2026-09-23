import { Layout, Menu, Result, Spin, Typography } from "antd";
import { useEffect, useState } from "react";
import { ApiError, getMe, type Me } from "./api";
import LogsPage from "./manage/LogsPage";
import PeoplePage from "./manage/PeoplePage";
import ProjectsPage from "./manage/ProjectsPage";
import SettingsPage from "./manage/SettingsPage";
import TasksPage from "./mini/TasksPage";
import OverviewPage from "./OverviewPage";
import SetupPage from "./setup/SetupPage";
import { inTelegram } from "./telegram";
import { usePath } from "./usePath";
import { useSetupState } from "./useSetupState";

const MANAGE_PAGES = [
  { key: "/", label: "Overview" },
  { key: "/projects", label: "Projects" },
  { key: "/people", label: "People" },
  { key: "/settings", label: "Settings" },
  { key: "/logs", label: "Logs" },
];

/** In Telegram a member gets their own tasks; an admin gets the group's tasks and the management pages too. */
const MY_TASKS = { key: "/tasks", label: "Миний даалгаврууд" };
const GROUP_TASKS = { key: "/group-tasks", label: "Бүх даалгавар" };

function Page({ path }: { path: string }) {
  switch (path) {
    case "/tasks":
      return <TasksPage scope="me" />;
    case "/group-tasks":
      return <TasksPage scope="group" />;
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

/** The pages inside Telegram, in menu order, for whoever is looking. */
function pagesFor(me: Me) {
  return me.admin ? [MY_TASKS, GROUP_TASKS, ...MANAGE_PAGES] : [MY_TASKS];
}

function Shell({ pages, selected, onSelect, children }: {
  pages: { key: string; label: string }[];
  selected: string;
  onSelect: (key: string) => void;
  children: React.ReactNode;
}) {
  return (
    <Layout style={{ minHeight: "100vh" }}>
      {inTelegram ? (
        // A phone has no room for a side menu, and Telegram's own chrome is already at the top.
        <Layout.Header style={{ padding: "0 8px", height: "auto", lineHeight: "normal" }}>
          <Menu mode="horizontal" selectedKeys={[selected]} items={pages} overflowedIndicator="…"
                onClick={({ key }) => onSelect(key)} />
        </Layout.Header>
      ) : (
        <Layout.Sider breakpoint="md" collapsedWidth={0} theme="light">
          <Typography.Title level={4} style={{ padding: "16px 24px", margin: 0 }}>Dispatch</Typography.Title>
          <Menu mode="inline" selectedKeys={[selected]} items={pages} onClick={({ key }) => onSelect(key)} />
        </Layout.Sider>
      )}
      <Layout.Content style={{ padding: inTelegram ? 12 : 24, maxWidth: 1200 }}>{children}</Layout.Content>
    </Layout>
  );
}

/** Inside Telegram: who is looking decides the menu, and the pages are the same ones `dispatch ui` serves. */
function MiniApp() {
  const [me, setMe] = useState<Me | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [path, navigate] = usePath();

  useEffect(() => {
    getMe().then(setMe, (failure: ApiError) => setError(failure));
  }, []);

  if (error) return <Result status="warning" title={titleFor(error)} subTitle={error.message} />;
  if (!me) return <Spin size="large" tip="Уншиж байна…"><div style={{ height: 200 }} /></Spin>;

  const pages = pagesFor(me);
  const page = pages.some((candidate) => candidate.key === path) ? path : MY_TASKS.key;
  return (
    <Shell pages={pages} selected={page} onSelect={navigate}>
      <Page path={page} />
    </Shell>
  );
}

/** The Mini App's own refusals read differently from a page that is simply broken. */
function titleFor(error: ApiError) {
  if (error.code === "expired") return "Дахин нээнэ үү";
  if (error.code === "not_a_member") return "Эрх алга";
  return "Dispatch-тай холбогдож чадсангүй";
}

/** Through `dispatch ui`: setup when there is no config yet, the management pages once there is. */
function WebUi() {
  const { state, error, refresh } = useSetupState();
  const [path, navigate] = usePath();

  if (error) return <Result status="warning" title="Cannot reach Dispatch" subTitle={error.message} />;
  if (!state) return <Spin size="large" tip="Loading…"><div style={{ height: 200 }} /></Spin>;

  const page = MANAGE_PAGES.some((candidate) => candidate.key === path) ? path : "/";
  return (
    <Shell pages={state.configExists ? MANAGE_PAGES : [{ key: "setup", label: "Setup" }]}
           selected={state.configExists ? page : "setup"}
           onSelect={(key) => state.configExists && navigate(key)}>
      {state.configExists ? <Page path={page} /> : <SetupPage onDone={() => { navigate("/"); void refresh(); }} />}
    </Shell>
  );
}

export default function App() {
  return inTelegram ? <MiniApp /> : <WebUi />;
}
