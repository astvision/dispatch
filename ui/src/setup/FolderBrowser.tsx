import { FolderOutlined } from "@ant-design/icons";
import { Alert, Button, Input, List, Space, Tag, Typography } from "antd";
import { useEffect, useState } from "react";
import { listFolders, type FolderListing } from "../api";
import { useT } from "../i18n/i18n";
import { useAction } from "../useAction";

/** Folders on the machine that runs Dispatch; the browser may be on another computer. */
export default function FolderBrowser({ onPick }: { onPick: (folder: string) => void }) {
  const t = useT();
  const [listing, setListing] = useState<FolderListing | null>(null);
  const [typedPath, setTypedPath] = useState("");
  const { busy, error, run } = useAction();

  const open = async (path: string | null) => {
    const found = await run(() => listFolders(path));
    if (found) {
      setListing(found);
      setTypedPath(found.path);
    }
  };

  useEffect(() => {
    void open(null);
    // once, at the home folder
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <label>
        <Typography.Text>{t("setup.folder")}</Typography.Text>
        <Space.Compact style={{ width: "100%" }}>
          <Input aria-label={t("setup.folder")} className="mono" value={typedPath} onChange={(e) => setTypedPath(e.target.value)} />
          <Button onClick={() => void open(typedPath)} disabled={busy}>{t("setup.go")}</Button>
        </Space.Compact>
      </label>
      {listing && (
        <Space wrap>
          <Button disabled={!listing.parent || busy} onClick={() => void open(listing.parent)}>{t("setup.up")}</Button>
          <Typography.Text code>{listing.path}</Typography.Text>
        </Space>
      )}
      {error && <Alert type="error" showIcon message={error.message} />}
      <List
        size="small"
        bordered
        loading={busy}
        locale={{ emptyText: t("setup.noFolders") }}
        dataSource={listing?.folders ?? []}
        style={{ maxHeight: 320, overflow: "auto" }}
        renderItem={(folder) => (
          <List.Item
            actions={folder.gitClone ? [<Button key="use" size="small" type="primary" aria-label={t("setup.useNamed", { name: folder.name })}
                                                onClick={() => onPick(folder.path)}>{t("setup.use")}</Button>] : []}
          >
            <Button type="link" icon={<FolderOutlined />} onClick={() => void open(folder.path)}>{folder.name}</Button>
            {folder.gitClone && <Tag>git clone</Tag>}
          </List.Item>
        )}
      />
      {listing?.truncated && <Typography.Text type="secondary">{t("setup.truncated")}</Typography.Text>}
    </Space>
  );
}
