import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ConnectionHealthAlerts, ConnectionHealthPanel, healthStatus, type ConnectionHealth } from "./connection-health";
const mocks = vi.hoisted(() => ({ data: [] as unknown[], error: undefined as unknown, post: vi.fn(), mutate: vi.fn(), toast: vi.fn() }));
vi.mock("swr", () => ({ default: () => ({ data: mocks.data, error: mocks.error, mutate: mocks.mutate }) }));
vi.mock("@/lib/api", () => ({ POST: mocks.post }));
vi.mock("@/hooks/use-toast", () => ({ useToast: () => ({ toast: mocks.toast }) }));
vi.mock("@/hooks/use-websocket", () => ({ useWebsocket: () => ({ connectionStatus: "Open" }) }));
vi.mock("@/components/proxys", () => ({ default: () => <div>Proxy editor</div> }));
const account = (status = "HEALTHY"): ConnectionHealth => ({ telegramId: "7", accountName: "Account A", status, proxy: "Proxy A", connectionState: "ready", probeStatus: "OK", checkedAt: Date.now(), lastSuccessAt: Date.now(), unhealthySince: 0, latencyMs: 100 });
afterEach(cleanup);
beforeEach(() => { vi.clearAllMocks(); mocks.error = undefined; mocks.data = [account()]; mocks.post.mockResolvedValue({}); mocks.mutate.mockResolvedValue(undefined); });
describe("connection health", () => {
  it("expires cached success and distinguishes a sleeping account", () => {
    expect(healthStatus(account(), false, Date.now() + 91_000)).toBe("UNKNOWN");
    expect(healthStatus(account(), true)).toBe("UNKNOWN");
    expect(healthStatus({ ...account("SLEEPING"), checkedAt: 0 })).toBe("SLEEPING");
  });
  it("checks the selected account and opens its proxy editor", async () => {
    render(<ConnectionHealthPanel />);
    expect(screen.getByText("Account A")).toBeVisible();
    expect(screen.getByText(/100 ms/)).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Check now" }));
    await waitFor(() => expect(mocks.post).toHaveBeenCalledWith("/telegram/7/check-health"));
    fireEvent.click(screen.getByRole("button", { name: "Proxy settings" }));
    expect(screen.getByText("Proxy editor")).toBeVisible();
  });
  it("shows only the affected account and sends one outage and recovery notification", async () => {
    mocks.data = [account("ERROR"), { ...account(), telegramId: "8", accountName: "Account B" }];
    const { rerender } = render(<ConnectionHealthAlerts />);
    expect(screen.getByRole("alert")).toHaveTextContent("Account A");
    expect(screen.getByRole("alert")).not.toHaveTextContent("Account B");
    rerender(<ConnectionHealthAlerts />);
    expect(mocks.toast).toHaveBeenCalledTimes(1);
    mocks.data = [account("CONNECTING")]; rerender(<ConnectionHealthAlerts />);
    mocks.data = [account()]; rerender(<ConnectionHealthAlerts />);
    await waitFor(() => expect(mocks.toast).toHaveBeenCalledWith(expect.objectContaining({ title: "Connection restored" })));
    expect(mocks.toast).toHaveBeenCalledTimes(2);
  });
  it("does not probe a sleeping account", () => {
    mocks.data = [account("SLEEPING")];
    render(<ConnectionHealthPanel />);
    expect(screen.getByRole("button", { name: "Check now" })).toBeDisabled();
    expect(mocks.post).not.toHaveBeenCalled();
  });
});
