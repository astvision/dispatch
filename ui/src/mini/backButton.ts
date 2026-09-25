import { useEffect, useRef } from "react";

/**
 * Telegram's own Back button and haptics, spoken to through Telegram's web-app event protocol
 * (core.telegram.org/api/web-events) rather than telegram-web-app.js: nothing is loaded from telegram.org (telegram.ts).
 *
 * Telegram's apps deliver events by calling window.Telegram.WebView.receiveEvent; Telegram Web posts them to the
 * frame as messages. Each app takes events through its own channel: TelegramWebviewProxy on phones and the desktop
 * app, postMessage to the parent frame on the web.
 */

interface TelegramBridge {
  TelegramWebviewProxy?: { postEvent(eventType: string, eventData: string): void };
  Telegram?: { WebView?: { receiveEvent?: (eventType: string, eventData: unknown) => void } };
}

const bridge = window as unknown as Window & TelegramBridge;

/**
 * Every screen and sheet that wants Back, in the order they appeared: the newest one that has somewhere to go takes the
 * press, so an open sheet closes before the screen under it goes back.
 */
const wanting: { current: (() => void) | null }[] = [];
let shown: boolean | null = null;

function top() {
  for (let index = wanting.length - 1; index >= 0; index--) {
    if (wanting[index].current) return wanting[index].current;
  }
  return null;
}

function received(eventType: string) {
  if (eventType === "back_button_pressed") top()?.();
}

let listening = false;
function listen() {
  if (listening) return;
  listening = true;
  const telegram = (bridge.Telegram ??= {});
  const webView = (telegram.WebView ??= {});
  const earlier = webView.receiveEvent;
  webView.receiveEvent = (eventType, eventData) => {
    received(eventType);
    earlier?.(eventType, eventData);
  };
  window.addEventListener("message", (event) => {
    if (typeof event.data !== "string") return;
    try {
      const message: unknown = JSON.parse(event.data);
      if (message && typeof message === "object" && "eventType" in message) received(String(message.eventType));
    } catch {
      // Not one of Telegram's: other frames may post anything.
    }
  });
}

export function post(eventType: string, eventData: object) {
  if (bridge.TelegramWebviewProxy) {
    bridge.TelegramWebviewProxy.postEvent(eventType, JSON.stringify(eventData));
  } else if (window.parent !== window) {
    window.parent.postMessage(JSON.stringify({ eventType, eventData }), "*");
  }
}

function sync() {
  const visible = top() !== null;
  if (visible === shown) return;
  shown = visible;
  post("web_app_setup_back_button", { is_visible: visible });
}

/** Shows Telegram's Back button while {@code onBack} is given, and hides it once nothing on screen has anywhere to go. */
export function useTelegramBackButton(onBack: (() => void) | null) {
  const entry = useRef<{ current: (() => void) | null }>({ current: onBack });
  entry.current.current = onBack;
  const visible = onBack !== null;

  useEffect(() => {
    listen();
    const own = entry.current;
    wanting.push(own);
    sync();
    return () => {
      wanting.splice(wanting.indexOf(own), 1);
      sync();
    };
  }, []);

  useEffect(sync, [visible]);
}

/** A tap felt in the hand: "success" once a decision is taken, "selection" as a choice is made. */
export function haptic(kind: "success" | "warning" | "selection") {
  post("web_app_trigger_haptic_feedback", kind === "selection"
    ? { type: "selection_change" }
    : { type: "notification", notification_type: kind });
}
