import { render } from "@testing-library/react";
import type { ReactNode } from "react";
import Shell, { DESKTOP_PAGES } from "./Shell";
import { DesktopProviders } from "./status";

/** For tests: a page as the desktop shows it, inside the shell, whose strip carries "Restart to apply" after a save. */
export function renderOnBoard(page: ReactNode) {
  return render(
    <DesktopProviders>
      <Shell pages={DESKTOP_PAGES} selected="/" onSelect={() => {}}>{page}</Shell>
    </DesktopProviders>,
  );
}
