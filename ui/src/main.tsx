import "@fontsource-variable/jetbrains-mono";
import "@fontsource-variable/onest";
import { ConfigProvider } from "antd";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./board.css";
import { appTheme } from "./theme";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ConfigProvider theme={appTheme()}>
      <App />
    </ConfigProvider>
  </StrictMode>,
);
