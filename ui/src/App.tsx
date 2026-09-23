import { SettingOutlined } from "@ant-design/icons";
import { Button, Dropdown, Flex, Layout, Menu, Result, Segmented, Spin, theme, Typography } from "antd";
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

/**
 * In Telegram a member gets their own tasks; an admin gets the group's tasks and the management pages too.
 * `short` is what fits the phone's switch, where the full label is cut off mid-word.
 */
const MY_TASKS = { key: "/tasks", label: "Миний даалгаврууд", short: "Минийх" };
const GROUP_TASKS = { key: "/group-tasks", label: "Бүх даалгавар", short: "Бүгд" };

function Page({ path, heading = true }: { path: string; heading?: boolean }) {
  switch (path) {
    case "/tasks":
      return <TasksPage scope="me" heading={heading} />;
    case "/group-tasks":
      return <TasksPage scope="group" heading={heading} />;
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
      <Layout.Sider breakpoint="md" collapsedWidth={0} theme="light">
        <Typography.Title level={4} style={{ padding: "16px 24px", margin: 0 }}>Dispatch</Typography.Title>
        <Menu mode="inline" selectedKeys={[selected]} items={pages} onClick={({ key }) => onSelect(key)} />
      </Layout.Sider>
      <Layout.Content style={{ padding: 24, maxWidth: 1200 }}>{children}</Layout.Content>
    </Layout>
  );
}

/**
 * The Mini App's own chrome. A phone has no room for seven menu items in a row, so the two task pages are a
 * Segmented control — thumb-sized and always visible — and an admin's management pages sit behind one more tap.
 * A member has only their own tasks, so they get no chrome at all.
 */
function MiniShell({ me, selected, onSelect, children }: {
  me: Me;
  selected: string;
  onSelect: (key: string) => void;
  children: React.ReactNode;
}) {
  const { token } = theme.useToken();

  // The page needs a surface of its own: antd paints its components, not the document, and a transparent body left
  // antd's dark text on whatever the webview happened to paint. Set on body too, so overscroll matches.
  useEffect(() => {
    document.body.style.background = token.colorBgContainer;
    document.body.style.color = token.colorText;
  }, [token.colorBgContainer, token.colorText]);

  return (
    <div style={{ minHeight: "100vh", padding: "8px 12px 24px", background: token.colorBgContainer, color: token.colorText }}>
      {me.admin && (
        <Flex gap={8} align="center" style={{ marginBottom: 12 }}>
          <Segmented
            block
            style={{ flex: 1 }}
            value={selected === GROUP_TASKS.key ? GROUP_TASKS.key : MY_TASKS.key}
            onChange={(value) => onSelect(String(value))}
            options={[MY_TASKS, GROUP_TASKS].map((page) => ({ label: page.short, value: page.key }))}
          />
          <Dropdown
            trigger={["click"]}
            menu={{ items: MANAGE_PAGES, selectedKeys: [selected], onClick: ({ key }) => onSelect(key) }}
          >
            <Button aria-label="Тохиргоо" icon={<SettingOutlined />} size="large" />
          </Dropdown>
        </Flex>
      )}
      {children}
    </div>
  );
}

/** Inside Telegram: who is looking decides what there is to reach, and the pages are `dispatch ui`'s own. */
function MiniApp() {
  const [me, setMe] = useState<Me | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [path, navigate] = usePath();

  useEffect(() => {
    getMe().then(setMe, (failure: ApiError) => setError(failure));
  }, []);

  if (error) return <Result status="warning" title={titleFor(error)} subTitle={error.message} />;
  if (!me) return <Spin size="large" tip="Уншиж байна…"><div style={{ height: 200 }} /></Spin>;

  // Telegram opens the Mini App at "/", which is Overview's own path, so the landing page is named rather than
  // matched: whoever opens this wants their tasks, not the machine's version number.
  const reachable = pagesFor(me);
  const page = path !== "/" && reachable.some((candidate) => candidate.key === path) ? path : MY_TASKS.key;
  return (
    <MiniShell me={me} selected={page} onSelect={navigate}>
      <Page path={page} heading={!me.admin} />
    </MiniShell>
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
