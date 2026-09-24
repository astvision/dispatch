import { Alert, Spin, Typography } from "antd";
import { useEffect, useState } from "react";
import { ApiError, getPrefs, savePrefs, type GroupAck } from "../api";
import { Choices } from "./FieldEditPage";

const OPTIONS: { value: GroupAck; label: string }[] = [
  { value: "reaction", label: "Реакц" },
  { value: "reactionAndLine", label: "Реакц + мөр" },
  { value: "silent", label: "Чимээгүй" },
];

const asApiError = (e: unknown) => (e instanceof ApiError ? e : new ApiError("unknown", String(e)));

/** Миний тохиргоо: how the caller's own group hears about their task, everyone's to read and change (G-1e). */
export default function PrefsPage() {
  const [pref, setPref] = useState<GroupAck | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let stopped = false;
    getPrefs().then((answer) => !stopped && setPref(answer.groupAck), (e: unknown) => !stopped && setError(asApiError(e)));
    return () => {
      stopped = true;
    };
  }, []);

  const onPick = async (value: GroupAck) => {
    const previous = pref;
    setPref(value);
    setSaving(true);
    setError(null);
    try {
      await savePrefs(value);
    } catch (e) {
      setPref(previous);
      setError(asApiError(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <Typography.Title level={4} style={{ margin: "16px 4px 0" }}>Миний тохиргоо</Typography.Title>
      <Typography.Text type="secondary" style={{ margin: "0 4px" }}>Группт даалгаврынхаа тухай яаж мэдэгдэх</Typography.Text>
      {error && <Alert type="error" showIcon message={error.message} style={{ margin: "12px 4px 0" }} />}
      {pref === null
        ? <div style={{ padding: 16 }}><Spin /></div>
        : <Choices options={OPTIONS} value={pref} busy={saving} onPick={(value) => void onPick(value)} />}
    </>
  );
}
