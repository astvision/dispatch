import { Alert, Button, Result, Space, Spin } from "antd";
import type { ReactNode } from "react";
import type { ApiError, ConfigView } from "../api";
import { useT } from "../i18n/i18n";

interface Props {
  /** The page's own "cannot show …", in the page's language. */
  cannotShow: string;
  config: ConfigView | null;
  loadError: ApiError | null;
  saveError: ApiError | null;
  reload: () => Promise<void>;
  children: (config: ConfigView) => ReactNode;
}

/** What every management page shares: loading, a load error and a refused save. "Restart to apply" is the shell's. */
export default function ManagedPage({ cannotShow, config, loadError, saveError, reload, children }: Props) {
  const t = useT();
  if (loadError) {
    return <Result status="warning" title={cannotShow} subTitle={loadError.message}
                   extra={<Button onClick={() => void reload()}>{t("common.tryAgain")}</Button>} />;
  }
  if (!config) return <Spin size="large" tip={t("app.loading")}><div style={{ height: 200 }} /></Spin>;

  return (
    <Space direction="vertical" size="large" style={{ width: "100%" }}>
      {saveError && (
        <Alert type="error" showIcon message={saveError.message}
               action={saveError.code === "changed" && <Button onClick={() => void reload()}>{t("managed.reload")}</Button>} />
      )}
      {children(config)}
    </Space>
  );
}
