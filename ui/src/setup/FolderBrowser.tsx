import { FolderOutlined } from "@ant-design/icons";
import { Alert, Button, Input, List, Space, Tag, Typography } from "antd";
import { useEffect, useRef, useState } from "react";
import { listFolders, type FolderListing } from "../api";
import { useT } from "../i18n/i18n";
import { useAction } from "../useAction";

interface Props {
  onPick: (folder: string) => void;
  /** Where the folders come from: setup's route by default, the management pages' own when adding a project. */
  list?: (path: string | null) => Promise<FolderListing>;
}

/** Folders on the machine that runs Dispatch; the browser may be on another computer. */
export default function FolderBrowser({ onPick, list = listFolders }: Props) {
  const t = useT();
  const [listing, setListing] = useState<FolderListing | null>(null);
  const [typedPath, setTypedPath] = useState("");
  const { busy, error, run } = useAction();
  const latest = useRef(0);
  const typed = useRef(false);

  const open = async (path: string | null) => {
    const asked = ++latest.current;
    const found = await run(() => list(path));
    // An older listing that answers late never replaces the one asked for after it.
    if (!found || asked !== latest.current) return;
    setListing(found);
    // The first listing, the home folder, does not overwrite a path typed while it loaded.
    if (path !== null || !typed.current) setTypedPath(found.path);
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
          <Input aria-label={t("setup.folder")} className="mono" value={typedPath} onChange={(e) => {
            typed.current = true;
            setTypedPath(e.target.value);
          }} />
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
