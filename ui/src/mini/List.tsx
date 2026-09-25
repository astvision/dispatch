import { CheckOutlined, RightOutlined } from "@ant-design/icons";
import { theme, Typography } from "antd";
import type { CSSProperties, ReactNode } from "react";

/**
 * The Mini App's building blocks, drawn after Telegram's own settings screens (BotFather's "My bots"): a header with
 * a round avatar, titled sections of rows on a raised surface, and a chevron on every row that leads somewhere.
 * Colours come from antd's tokens, which follow Telegram's theme (theme.ts).
 */

// Telegram's peer colours, darkened so a white initial on them keeps a readable contrast in both themes.
const AVATAR_COLOURS = ["#C9444D", "#B85F24", "#7E6BC4", "#3F8A2E", "#23827F", "#3B7FB6", "#B8467F"];

/** The same name always gets the same colour, so a project is recognisable at a glance from screen to screen. */
export function avatarColour(name: string) {
  let hash = 0;
  for (const char of name) hash = (hash * 31 + char.codePointAt(0)!) | 0;
  return AVATAR_COLOURS[Math.abs(hash) % AVATAR_COLOURS.length];
}

export function Avatar({ name, size = 40, photo }: { name: string; size?: number; photo?: string | null }) {
  if (photo) {
    return <img src={photo} alt="" width={size} height={size} style={{ flex: "none", borderRadius: "50%", objectFit: "cover" }} />;
  }
  const initials = name.replace(/[^\p{L}\p{N}]+/gu, " ").trim().split(" ").slice(0, 2).map((word) => word[0]).join("")
    .toUpperCase() || "?";
  return (
    <span aria-hidden="true" style={{
      width: size, height: size, flex: "none", borderRadius: "50%", background: avatarColour(name), color: "#fff",
      display: "grid", placeItems: "center", fontWeight: 600, fontSize: Math.round(size * 0.4), letterSpacing: 0.3,
    }}>
      {initials}
    </span>
  );
}

/** The top of a screen: its avatar, its name, and one line about it. */
export function Header({ name, title, subtitle, photo }: { name: string; title: string; subtitle?: ReactNode; photo?: string | null }) {
  const { token } = theme.useToken();
  return (
    <header style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 8, padding: "20px 16px 8px",
                     textAlign: "center" }}>
      <Avatar name={name} size={80} photo={photo} />
      <Typography.Title level={3} style={{ margin: "4px 0 0", overflowWrap: "anywhere" }}>{title}</Typography.Title>
      {subtitle && <div style={{ color: token.colorTextSecondary, fontSize: 14, maxWidth: "40ch" }}>{subtitle}</div>}
    </header>
  );
}

/** A titled group of rows on the raised surface. */
export function Section({ title, children }: { title?: string; children: ReactNode }) {
  const { token } = theme.useToken();
  return (
    <section style={{ marginTop: 20 }} aria-label={title}>
      {title && <h2 style={{ fontSize: 16, fontWeight: 600, margin: "0 4px 8px", color: `var(--ink, ${token.colorText})` }}>{title}</h2>}
      <div style={{ background: `var(--slip, ${token.colorBgContainer})`, borderRadius: 12, overflow: "hidden" }}>{children}</div>
    </section>
  );
}

interface RowProps {
  title: ReactNode;
  subtitle?: ReactNode;
  /** The current value, shown on the right as in Telegram's settings. */
  value?: ReactNode;
  /** An avatar or icon on the left. */
  leading?: ReactNode;
  /** Red, for the one row that removes something. */
  danger?: boolean;
  /** A row that shows the chosen option in a list of choices. */
  checked?: boolean;
  onClick?: () => void;
  ariaLabel?: string;
}

/** One line of a Section. A row with {@code onClick} is a real button, reachable by keyboard like any other. */
export function Row({ title, subtitle, value, leading, danger = false, checked, onClick, ariaLabel }: RowProps) {
  const { token } = theme.useToken();
  const style: CSSProperties = {
    display: "flex", alignItems: "center", gap: 12, width: "100%", minHeight: 52, padding: "8px 16px",
    border: 0, font: "inherit", textAlign: "left", color: danger ? token.colorError : token.colorText,
    cursor: onClick ? "pointer" : "default",
  };
  const content = (
    <>
      {leading}
      <span style={{ flex: 1, minWidth: 0 }}>
        <span style={{ display: "block", fontSize: 16, lineHeight: 1.3, overflowWrap: "anywhere" }}>{title}</span>
        {subtitle && (
          <span style={{ display: "block", fontSize: 13.5, color: token.colorTextSecondary, overflowWrap: "anywhere" }}>
            {subtitle}
          </span>
        )}
      </span>
      {value !== undefined && value !== null && (
        <span style={{ color: token.colorTextSecondary, fontSize: 15, textAlign: "right", maxWidth: "45%",
                       overflowWrap: "anywhere" }}>
          {value}
        </span>
      )}
      {checked && <CheckOutlined aria-hidden="true" style={{ color: token.colorPrimary, fontSize: 16 }} />}
      {onClick && checked === undefined && !danger && (
        <RightOutlined aria-hidden="true" style={{ color: token.colorTextQuaternary, fontSize: 12 }} />
      )}
    </>
  );
  return onClick
    ? <button type="button" className="mini-row" style={style} onClick={onClick} aria-label={ariaLabel}
              aria-pressed={checked}>{content}</button>
    : <div className="mini-row" style={style}>{content}</div>;
}

/** Hover, focus and the hairline between rows: the few things inline styles cannot say. */
export function ListStyles() {
  const { token } = theme.useToken();
  return (
    <style>{`
      .mini-row { position: relative; background: transparent; }
      .mini-row + .mini-row::before { content: ""; position: absolute; top: 0; left: 16px; right: 0;
                                      border-top: 1px solid ${token.colorSplit}; }
      button.mini-row:hover { background: ${token.colorFillTertiary}; }
      button.mini-row:focus-visible { outline: 2px solid ${token.colorPrimary}; outline-offset: -2px; }
      @media (prefers-reduced-motion: no-preference) { button.mini-row { transition: background .15s; } }
    `}</style>
  );
}
