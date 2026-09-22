/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development, `npm run dev` serves the pages and passes /api on to a running `dispatch ui --no-browser`.
// Open the link that dispatch ui printed once: its cookie is for 127.0.0.1 on every port, so the dev server's
// requests carry it too. Then work on http://127.0.0.1:5173.
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
  },
});
