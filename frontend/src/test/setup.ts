import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";
import { setTokenProvider } from "../api/client";

setTokenProvider(async () => "test-token");

// jsdom has <dialog> but not its modal API.
HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
  this.setAttribute("open", "");
};
HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
  this.removeAttribute("open");
  this.dispatchEvent(new Event("close"));
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
