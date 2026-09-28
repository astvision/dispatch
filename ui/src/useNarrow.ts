import { useEffect, useState } from "react";

const NARROW = "(max-width: 640px)";

/** Below 640 px a side panel takes the whole width (the projects' and the tasks' panels). */
export function useNarrow() {
  const [narrow, setNarrow] = useState(() => window.matchMedia(NARROW).matches);
  useEffect(() => {
    const list = window.matchMedia(NARROW);
    const change = () => setNarrow(list.matches);
    list.addEventListener("change", change);
    return () => list.removeEventListener("change", change);
  }, []);
  return narrow;
}
