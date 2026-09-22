/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development, `npm run dev` serves the pages and passes /api on to a running `dispatch ui --no-browser`.
// Run that on the default port (7878, matching the proxy target below) and open the link it printed once: the
// browser sends its dispatch_session_7878 cookie to 127.0.0.1 on any port, so the dev server's own requests carry
// it too. Then work on http://127.0.0.1:5173.
export default defineConfig({
  plugins: [react()],
  server: {
    host: "127.0.0.1",
    proxy: { "/api": { target: "http://127.0.0.1:7878", changeOrigin: true } },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test-setup.ts"],
    // e2e/*.spec.ts are Playwright's (npm run e2e), not Vitest's.
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
