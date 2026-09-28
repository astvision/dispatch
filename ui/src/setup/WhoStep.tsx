import { Alert, Button, Radio, Space } from "antd";
import { useState } from "react";
import { chooseTeam, type SetupState } from "../api";
import { useT } from "../i18n/i18n";
import { useAction } from "../useAction";

export default function WhoStep({ state, next }: { state: SetupState; next: () => void }) {
  const t = useT();
  const [team, setTeam] = useState(state.team);
  const { busy, error, run } = useAction();

  const submit = async () => {
    if ((await run(() => chooseTeam(team))) !== undefined) next();
  };

  return (
    <Space direction="vertical" size="middle" style={{ width: "100%" }}>
      <Radio.Group value={team} onChange={(e) => setTeam(e.target.value)}>
        <Space direction="vertical">
          <Radio value={false}>{t("setup.justMe")}</Radio>
          <Radio value={true}>{t("setup.myTeam")}</Radio>
        </Space>
      </Radio.Group>
      {error && <Alert type="error" showIcon message={error.message} />}
      <Button type="primary" loading={busy} onClick={() => void submit()}>{t("common.next")}</Button>
    </Space>
  );
}
