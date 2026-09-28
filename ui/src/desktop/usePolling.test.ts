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

test("while hidden it keeps a slower beat when given one", async () => {
  vi.useFakeTimers();
  const read = vi.fn().mockResolvedValue(1);
  renderHook(() => usePolling(read, 5000, undefined, 30_000));
  await act(() => vi.advanceTimersByTimeAsync(0));
  act(() => setVisibility("hidden"));
  await act(() => vi.advanceTimersByTimeAsync(5000));
  expect(read).toHaveBeenCalledTimes(2); // the beat set while in view
  await act(() => vi.advanceTimersByTimeAsync(29_000));
  expect(read).toHaveBeenCalledTimes(2);
  await act(() => vi.advanceTimersByTimeAsync(1000));
  expect(read).toHaveBeenCalledTimes(3);
});

test("a tab shown again while a reading is out starts no second beat", async () => {
  vi.useFakeTimers();
  let answer: (value: number) => void = () => {};
  const read = vi.fn()
    .mockResolvedValueOnce(1)
    .mockImplementationOnce(() => new Promise<number>((done) => { answer = done; }))
    .mockResolvedValue(3);
  renderHook(() => usePolling(read, 5000));
  await act(() => vi.advanceTimersByTimeAsync(5000)); // the first answer, and the second reading out
  act(() => setVisibility("hidden"));
  act(() => setVisibility("visible"));
  await act(async () => answer(2));
  await act(() => vi.advanceTimersByTimeAsync(5000));
  expect(read).toHaveBeenCalledTimes(3);
  await act(() => vi.advanceTimersByTimeAsync(5000));
  expect(read).toHaveBeenCalledTimes(4);
});
