import "@testing-library/jest-dom/vitest";

// Ant Design reads the screen size through matchMedia, which jsdom lacks.
Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});

// Ant Design's popups (Popconfirm, Select) measure themselves with ResizeObserver, which jsdom lacks.
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
};

// Ant Design's Table asks for its scrollbar's pseudo-element style, which jsdom does not implement and reports as noise.
const computedStyle = window.getComputedStyle.bind(window);
window.getComputedStyle = (element: Element, _pseudoElement?: string | null) => computedStyle(element);
