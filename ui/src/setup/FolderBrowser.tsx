import { FolderOutlined } from "@ant-design/icons";
import { Alert, Button, Input, List, Space, Tag, Typography } from "antd";
import { useEffect, useState } from "react";
import { listFolders, type FolderListing } from "../api";
import { useAction } from "../useAction";

/** Folders on the machine that runs Dispatch; the browser may be on another computer. */
export default function FolderBrowser({ onPick }: { onPick: (folder: string) => void }) {
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
        <Typography.Text>Folder</Typography.Text>
        <Space.Compact style={{ width: "100%" }}>
          <Input aria-label="Folder" value={typedPath} onChange={(e) => setTypedPath(e.target.value)} />
          <Button onClick={() => void open(typedPath)} disabled={busy}>Go</Button>
        </Space.Compact>
      </label>
      {listing && (
        <Space wrap>
          <Button disabled={!listing.parent || busy} onClick={() => void open(listing.parent)}>Up</Button>
          <Typography.Text code>{listing.path}</Typography.Text>
        </Space>
      )}
      {error && <Alert type="error" showIcon message={error.message} />}
      <List
        size="small"
        bordered
        loading={busy}
        locale={{ emptyText: "No folders here" }}
        dataSource={listing?.folders ?? []}
        style={{ maxHeight: 320, overflow: "auto" }}
        renderItem={(folder) => (
          <List.Item
            actions={folder.gitClone ? [<Button key="use" size="small" type="primary" aria-label={`Use ${folder.name}`}
                                                onClick={() => onPick(folder.path)}>Use</Button>] : []}
          >
            <Button type="link" icon={<FolderOutlined />} onClick={() => void open(folder.path)}>{folder.name}</Button>
            {folder.gitClone && <Tag>git clone</Tag>}
          </List.Item>
        )}
      />
      {listing?.truncated && <Typography.Text type="secondary">Only the first 500 folders are shown; go into a folder below to see its own.</Typography.Text>}
    </Space>
  );
}
