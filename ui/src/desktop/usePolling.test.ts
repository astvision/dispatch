import { act, renderHook } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import { ApiError } from "../api";
import { usePolling } from "./usePolling";

let visibility: DocumentVisibilityState = "visible";
Object.defineProperty(document, "visibilityState", { configurable: true, get: () => visibility });

function setVisibility(next: DocumentVisibilityState) {
  visibility = next;
  document.dispatchEvent(new Event("visibilitychange"));
}

afterEach(() => {
  vi.useRealTimers();
  setVisibility("visible");
});

test("reads now and again after each answer while in view, and not while hidden", async () => {
  vi.useFakeTimers();
  const read = vi.fn().mockResolvedValue(1);
  renderHook(() => usePolling(read, 5000));
  await act(() => vi.advanceTimersByTimeAsync(0));
  expect(read).toHaveBeenCalledTimes(1);
  await act(() => vi.advanceTimersByTimeAsync(5000));
  expect(read).toHaveBeenCalledTimes(2);
  act(() => setVisibility("hidden"));
  await act(() => vi.advanceTimersByTimeAsync(20_000));
  expect(read).toHaveBeenCalledTimes(2);
  act(() => setVisibility("visible"));
  await act(() => vi.advanceTimersByTimeAsync(0));
  expect(read).toHaveBeenCalledTimes(3);
});

test("a failure keeps the last answer and is replaced by the next success", async () => {
  vi.useFakeTimers();
  const read = vi.fn()
    .mockResolvedValueOnce(1)
    .mockRejectedValueOnce(new ApiError("bot_not_running", "The bot is not running"))
    .mockResolvedValueOnce(2);
  const { result } = renderHook(() => usePolling(read, 5000));
  await act(() => vi.advanceTimersByTimeAsync(0));
  expect(result.current.data).toBe(1);
  await act(() => vi.advanceTimersByTimeAsync(5000));
  expect(result.current.data).toBe(1);
  expect(result.current.error?.code).toBe("bot_not_running");
  await act(() => vi.advanceTimersByTimeAsync(5000));
  expect(result.current.data).toBe(2);
  expect(result.current.error).toBeNull();
});
