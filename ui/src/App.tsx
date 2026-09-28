import { LeftOutlined } from "@ant-design/icons";
import { Button, Result, Spin, theme } from "antd";
import { lazy, Suspense, useContext, useEffect, useState } from "react";
import { ApiError, getMe, type Me } from "./api";
import Shell, { DESKTOP_PAGES, type ShellPage } from "./desktop/Shell";
import { DesktopProviders, useDesktopStatus } from "./desktop/status";
import { LanguageProvider, useT } from "./i18n/i18n";
import { post, useTelegramBackButton } from "./mini/backButton";
import HomePage from "./mini/HomePage";
import { ListStyles, Section } from "./mini/List";
import { ADMIN_PAGES, parentOf, projectPath, screenOf, type PagePath, type Screen } from "./mini/paths";
import "./mini/world.css";
import { paletteFor, worldStyle } from "./mini/world";
import RestartNotice from "./RestartNotice";
import { RestartContext, useRestartNeeded } from "./restart";
import { inTelegram, prefersDark } from "./telegram";
import { usePath } from "./usePath";
import { useSetupState } from "./useSetupState";

// Only the shell and Home load up front; every other page comes when it is opened, so the Mini App starts quickly
// over the owner's tunnel.
const LogsPage = lazy(() => import("./manage/LogsPage"));
const PeoplePage = lazy(() => import("./manage/PeoplePage"));
const ProjectsPage = lazy(() => import("./manage/ProjectsPage"));
const SettingsPage = lazy(() => import("./manage/SettingsPage"));
const AddProjectPage = lazy(() => import("./mini/AddProjectPage"));
const FieldEditPage = lazy(() => import("./mini/FieldEditPage"));
const GroupsPage = lazy(() => import("./mini/GroupsPage"));
const GuidePage = lazy(() => import("./mini/GuidePage"));
const PrefsPage = lazy(() => import("./mini/PrefsPage"));
const ProjectPage = lazy(() => import("./mini/ProjectPage"));
const MiniProjectsPage = lazy(() => import("./mini/ProjectsPage"));
const TasksPage = lazy(() => import("./mini/TasksPage"));
const OverviewPage = lazy(() => import("./OverviewPage"));
const SetupPage = lazy(() => import("./setup/SetupPage"));

const SETUP_PAGES: ShellPage[] = [{ key: "setup", label: "nav.setup" }];

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

const TASK_TITLES: Record<string, string> = { "/tasks": "Миний даалгаврууд", "/group-tasks": "Бүх даалгавар" };

/** One of `dispatch ui`'s own pages inside the Mini App; the task lists sit on the raised surface like the rest. */
function MiniPage({ path }: { path: PagePath }) {
  const { token } = theme.useToken();
  if (path === "/tasks" || path === "/group-tasks") {
    return (
      <Section title={TASK_TITLES[path]}>
        <div style={{ padding: "4px 16px 8px", background: token.colorBgContainer }}>
          <Page path={path} heading={false} />
        </div>
      </Section>
    );
  }
  if (path === "/groups") return <GroupsPage />;
  if (path === "/prefs") return <PrefsPage />;
  if (path === "/guide") return <GuidePage />;
  return <div style={{ paddingTop: 12 }}><Page path={path === "/overview" ? "/" : path} /></div>;
}

function MiniScreen({ me, screen, navigate }: { me: Me; screen: Screen; navigate: (path: string) => void }) {
  switch (screen.kind) {
    case "home":
      return <HomePage me={me} navigate={navigate} />;
    case "page":
      return screen.path === "/projects" ? <MiniProjectsPage me={me} navigate={navigate} /> : <MiniPage path={screen.path} />;
    case "add":
      return <AddProjectPage navigate={navigate} />;
    case "project":
      return <ProjectPage key={screen.name} me={me} name={screen.name} navigate={navigate} />;
    case "field":
      return <FieldEditPage key={`${screen.name}/${screen.field}`} name={screen.name} field={screen.field}
                            back={() => navigate(projectPath(screen.name))} />;
  }
}

/** Whether {@code me} may open {@code screen}: a member has the projects and their own tasks, an admin everything. */
function reachable(me: Me, screen: Screen) {
  if (me.admin) return true;
  if (screen.kind === "page") return !ADMIN_PAGES.includes(screen.path);
  return screen.kind === "home" || screen.kind === "project";
}

/**
 * The Mini App's own chrome: Telegram's colours as the page's world (world.ts), Telegram's Back button in its header,
 * and "restart to apply" kept above every screen once something was saved.
 */
function MiniShell({ back, children }: { back: (() => void) | null; children: React.ReactNode }) {
  const { token } = theme.useToken();
  const restart = useContext(RestartContext);
  useTelegramBackButton(back);
  const { ground, ink } = paletteFor(prefersDark);

  // The page needs a surface of its own: antd paints its components, not the document, and a transparent body left
  // antd's dark text on whatever the webview happened to paint. Set on body too, so overscroll matches. Telegram's own
  // header and background take the ground, so its bar does not sit in another colour above the page.
  useEffect(() => {
    document.body.style.background = ground;
    document.body.style.color = ink;
    document.body.style.margin = "0";
    post("web_app_set_header_color", { color: ground });
    post("web_app_set_background_color", { color: ground });
    post("web_app_set_bottom_bar_color", { color: ground });
  }, [ground, ink]);

  return (
    <main className="mini-world" style={{ ...worldStyle(prefersDark), padding: "8px 16px calc(24px + env(safe-area-inset-bottom))",
                                          fontFamily: token.fontFamily }}>
      <ListStyles />
      <div style={{ maxWidth: 640, margin: "0 auto" }}>
        {back && (
          // Telegram's own Back button is the way back; this one is for a client that does not show it.
          <Button type="link" icon={<LeftOutlined />} onClick={back} aria-label="Буцах" style={{ paddingInline: 0 }}>Буцах</Button>
        )}
        {restart.installed !== null && <div style={{ marginTop: 8 }}><RestartNotice installed={restart.installed} /></div>}
        <Suspense fallback={<div style={{ padding: 24, textAlign: "center" }}><Spin /></div>}>{children}</Suspense>
      </div>
    </main>
  );
}

/** Inside Telegram: who is looking decides what there is to reach. */
function MiniApp() {
  const [me, setMe] = useState<Me | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [path, navigate] = usePath();
  const restart = useRestartNeeded();

  useEffect(() => {
    getMe().then(setMe, (failure: ApiError) => setError(failure));
  }, []);

  if (error) return <Result status="warning" title={titleFor(error)} subTitle={error.message} />;
  if (!me) return <Spin size="large" tip="Уншиж байна…"><div style={{ height: 200 }} /></Spin>;

  const wanted = screenOf(path);
  const screen: Screen = reachable(me, wanted) ? wanted : { kind: "home" };
  const parent = parentOf(screen);
  return (
    <RestartContext.Provider value={restart}>
      <MiniShell back={parent === null ? null : () => navigate(parent)}>
        <MiniScreen me={me} screen={screen} navigate={navigate} />
      </MiniShell>
    </RestartContext.Provider>
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
  const t = useT();
  const { state, error, refresh } = useSetupState();
  const status = useDesktopStatus();
  const [path, navigate] = usePath();

  if (error) return <Result status="warning" title={t("app.unreachable")} subTitle={error.message} />;
  if (!state) return <Spin size="large" tip={t("app.loading")}><div style={{ height: 200 }} /></Spin>;

  const page = DESKTOP_PAGES.some((candidate) => candidate.key === path) ? path : "/";
  const setUp = () => {
    navigate("/");
    void refresh();
    // The strip read the overview before there was a config.
    void status.reload();
  };
  return (
    <Shell pages={state.configExists ? DESKTOP_PAGES : SETUP_PAGES}
           selected={state.configExists ? page : "setup"}
           onSelect={(key) => state.configExists && navigate(key)}>
      {state.configExists ? <Page path={page} /> : <SetupPage onDone={setUp} />}
    </Shell>
  );
}

/** The desktop speaks the browser's language or the one chosen; the Mini App speaks the bot's, Mongolian. */
export default function App() {
  return inTelegram
    ? <LanguageProvider fixed="mn"><MiniApp /></LanguageProvider>
    : <LanguageProvider><DesktopProviders><WebUi /></DesktopProviders></LanguageProvider>;
}
