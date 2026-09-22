import { useCallback, useEffect, useState } from "react";

/** The page's path, and a way to move to another without reloading; the server answers index.html for every page path. */
export function usePath(): [string, (path: string) => void] {
  const [path, setPath] = useState(window.location.pathname);

  useEffect(() => {
    const onBack = () => setPath(window.location.pathname);
    window.addEventListener("popstate", onBack);
    return () => window.removeEventListener("popstate", onBack);
  }, []);

  const navigate = useCallback((to: string) => {
    if (to !== window.location.pathname) window.history.pushState(null, "", to);
    setPath(to);
  }, []);

  return [path, navigate];
}
