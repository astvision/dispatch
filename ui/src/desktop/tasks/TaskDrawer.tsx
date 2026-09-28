import { Drawer } from "antd";
import { useNarrow } from "../../useNarrow";
import TaskView from "./TaskView";

/** One task in the side panel (D-2): the Tasks page's and the Overview's; its Details opens the task's own page. */
export default function TaskDrawer({ taskId, onClose, onChanged, navigate }: {
  taskId: number | null;
  onClose: () => void;
  onChanged: () => void;
  navigate: (path: string) => void;
}) {
  const narrow = useNarrow();
  return (
    <Drawer open={taskId !== null} onClose={onClose} placement="right" size={narrow ? "100%" : 520} destroyOnHidden
            title={taskId === null ? null : `#${taskId}`}>
      {taskId !== null && (
        <TaskView key={taskId} taskId={taskId} layout="panel" onChanged={onChanged} onDetails={() => navigate(`/tasks/${taskId}`)} />
      )}
    </Drawer>
  );
}
