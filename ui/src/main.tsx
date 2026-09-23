import { ConfigProvider } from "antd";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import { telegramTheme } from "./theme";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ConfigProvider theme={telegramTheme()}>
      <App />
    </ConfigProvider>
  </StrictMode>,
);
