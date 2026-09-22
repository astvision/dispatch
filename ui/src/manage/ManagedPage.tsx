import { Alert, Button, Result, Space, Spin } from "antd";
import type { ReactNode } from "react";
import type { ApiError, ConfigView } from "../api";
import RestartNotice from "../RestartNotice";

interface Props {
  title: string;
  config: ConfigView | null;
  loadError: ApiError | null;
  saveError: ApiError | null;
  saved: boolean;
  reload: () => Promise<void>;
  children: (config: ConfigView) => ReactNode;
}

/** What every management page shares: loading, a load error, a refused save, and "Restart to apply" after a save. */
export default function ManagedPage({ title, config, loadError, saveError, saved, reload, children }: Props) {
  if (loadError) {
    return <Result status="warning" title={`Cannot show ${title}`} subTitle={loadError.message}
                   extra={<Button onClick={() => void reload()}>Try again</Button>} />;
  }
  if (!config) return <Spin size="large" tip="Loading…"><div style={{ height: 200 }} /></Spin>;

  return (
    <Space direction="vertical" size="large" style={{ width: "100%" }}>
      {saveError && (
        <Alert type="error" showIcon message={saveError.message}
               action={saveError.code === "changed" && <Button onClick={() => void reload()}>Reload</Button>} />
      )}
      {saved && <RestartNotice installed={config.service.installed} />}
      {children(config)}
    </Space>
  );
}
