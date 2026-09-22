import { Button } from "antd";

// Replaced in UI-2 Task 6.
export default function Step({ next }: { next: () => void }) {
  return <Button onClick={next}>Next</Button>;
}
