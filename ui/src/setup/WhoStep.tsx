import { Alert, Button, Radio, Space } from "antd";
import { useState } from "react";
import { chooseTeam, type SetupState } from "../api";
import { useAction } from "../useAction";

export default function WhoStep({ state, next }: { state: SetupState; next: () => void }) {
  const [team, setTeam] = useState(state.team);
  const { busy, error, run } = useAction();

  const submit = async () => {
    if ((await run(() => chooseTeam(team))) !== undefined) next();
  };

  return (
    <Space direction="vertical" size="middle" style={{ width: "100%" }}>
      <Radio.Group value={team} onChange={(e) => setTeam(e.target.value)}>
        <Space direction="vertical">
          <Radio value={false}>Just me: tasks and results stay in your private chat with the bot</Radio>
          <Radio value={true}>My team: teammates join when you approve them; a team group can see announcements</Radio>
        </Space>
      </Radio.Group>
      {error && <Alert type="error" showIcon message={error.message} />}
      <Button type="primary" loading={busy} onClick={() => void submit()}>Next</Button>
    </Space>
  );
}
