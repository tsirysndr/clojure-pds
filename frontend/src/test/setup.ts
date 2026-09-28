import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, vi } from "vitest";
import { MotionGlobalConfig } from "framer-motion";
import { server } from "../mocks/server";
import { resetScenario } from "../mocks/handlers";

MotionGlobalConfig.skipAnimations = true;

beforeAll(() => {
  server.listen({ onUnhandledRequest: "error" });

  Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }),
  });
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  resetScenario();
  window.history.replaceState({}, "", "/account");
  vi.clearAllMocks();
});

afterAll(() => server.close());
