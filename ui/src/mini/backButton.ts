import { useEffect, useRef } from "react";

/**
 * Telegram's own Back button, in the Mini App's header, spoken to through Telegram's web-app event protocol
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
let pressed: (() => void) | null = null;

function received(eventType: string) {
  if (eventType === "back_button_pressed") pressed?.();
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

function post(eventType: string, eventData: object) {
  if (bridge.TelegramWebviewProxy) {
    bridge.TelegramWebviewProxy.postEvent(eventType, JSON.stringify(eventData));
  } else if (window.parent !== window) {
    window.parent.postMessage(JSON.stringify({ eventType, eventData }), "*");
  }
}

/** Shows Telegram's Back button while {@code onBack} is given, and hides it on a screen that has nowhere to go back to. */
export function useTelegramBackButton(onBack: (() => void) | null) {
  const latest = useRef(onBack);
  latest.current = onBack;
  const visible = onBack !== null;

  useEffect(() => {
    listen();
    const handler = () => latest.current?.();
    pressed = handler;
    post("web_app_setup_back_button", { is_visible: visible });
    return () => {
      if (pressed === handler) pressed = null;
    };
  }, [visible]);
}
