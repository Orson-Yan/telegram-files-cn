import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CloudArchiveManager } from "./cloud-archive-manager";
import { createDefaultAuto } from "@/lib/automation";
import type { CloudArchiveOverview } from "@/lib/types";

const mocks = vi.hoisted(() => ({
  post: vi.fn(),
  request: vi.fn(),
  reload: vi.fn(),
  toast: vi.fn(),
  accounts: [{ id: "7", name: "Test account" }],
  pickedChatIds: ["100", "400"],
}));

let overview: CloudArchiveOverview;

vi.mock("@/lib/api", () => ({ POST: mocks.post, request: mocks.request }));
vi.mock("@/hooks/use-toast", () => ({ useToast: () => ({ toast: mocks.toast }) }));
vi.mock("@/hooks/use-telegram-account", () => ({
  useTelegramAccount: () => ({ getAccounts: () => mocks.accounts }),
}));
vi.mock("swr", () => ({
  default: (key: string) => ({
    data: key === "/cloud-archive/overview" ? overview : [],
    isLoading: false,
    mutate: mocks.reload,
  }),
}));
vi.mock("@/components/rule-chat-picker", () => ({
  RuleChatPicker: ({ value, disabled, onChange }: {
    value: string;
    disabled?: boolean;
    onChange: (id: string, chat: { isForum: boolean }) => void;
  }) => (
    <button disabled={disabled} onClick={() => onChange(value ? "400" : mocks.pickedChatIds.shift()!, { isForum: false })}>
      {value || "Choose chat"}
    </button>
  ),
}));

describe("cloud archive rules with a shared source", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.pickedChatIds = ["100", "400"];
    const auto = createDefaultAuto();
    auto.archive = { enabled: true, rule: { ...auto.archive.rule, targetChatId: 200 } };
    auto.archiveRules = [{ enabled: true, rule: { ...auto.archive.rule, targetChatId: 300 } }];
    overview = {
      statistics: { total: 0, completed: 0, pending: 0, failed: 0, skipped: 0 },
      rules: [auto.archive, ...auto.archiveRules].map((config) => ({
        telegramId: "7",
        accountName: "Test account",
        sourceChatId: "100",
        sourceChatName: "Shared source",
        targetChatId: String(config.rule.targetChatId),
        targetChatName: `Destination ${config.rule.targetChatId}`,
        targetDownloadEnabled: true,
        ...config,
      })),
    };
    mocks.request.mockResolvedValue([{ id: "100", auto }]);
    mocks.post.mockResolvedValue({});
    mocks.reload.mockResolvedValue(undefined);
  });

  afterEach(cleanup);

  it("shows both routes and pauses only the selected destination", async () => {
    render(<CloudArchiveManager />);
    expect(screen.getByText("Destination 200")).toBeVisible();
    expect(screen.getByText("Destination 300")).toBeVisible();
    fireEvent.click(screen.getAllByRole("button", { name: "Pause" })[1]!);
    await waitFor(() => expect(mocks.post).toHaveBeenCalledTimes(1));
    expect(mocks.post.mock.calls[0]).toEqual([
      "/7/file/update-auto-settings?telegramId=7&chatId=100",
      {
        archiveUpdate: expect.objectContaining({
          enabled: false,
          rule: expect.objectContaining({ targetChatId: 300 }),
        }),
        previousTargetChatId: "300",
      },
    ]);
  });

  it("edits the second route using its original destination identity", async () => {
    render(<CloudArchiveManager />);
    fireEvent.click(screen.getAllByRole("button", { name: "Edit" })[1]!);
    expect(screen.getByRole("button", { name: "100" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "300" }));
    fireEvent.click(screen.getByRole("button", { name: "Save rule" }));
    await waitFor(() => expect(mocks.post).toHaveBeenCalledTimes(1));
    expect(mocks.post.mock.calls[0]?.[1]).toEqual({
      archiveUpdate: expect.objectContaining({
        rule: expect.objectContaining({ targetChatId: 400 }),
      }),
      previousTargetChatId: "300",
    });
  });

  it("adds another destination to the shared source with a destination-only update", async () => {
    render(<CloudArchiveManager />);
    fireEvent.click(screen.getByRole("button", { name: "New rule" }));
    fireEvent.click(screen.getAllByRole("button", { name: "Choose chat" })[0]!);
    fireEvent.click(screen.getByRole("button", { name: "Choose chat" }));
    fireEvent.click(screen.getByRole("button", { name: "Save rule" }));
    await waitFor(() => expect(mocks.post).toHaveBeenCalledTimes(1));
    expect(mocks.post.mock.calls[0]).toEqual([
      "/7/file/update-auto-settings?telegramId=7&chatId=100",
      {
        archiveUpdate: expect.objectContaining({ rule: expect.objectContaining({ targetChatId: 400 }) }),
        previousTargetChatId: undefined,
      },
    ]);
  });

  it("requires confirmation and deletes only the second destination", async () => {
    render(<CloudArchiveManager />);
    fireEvent.click(screen.getAllByRole("button", { name: "Delete rule" })[1]!);
    expect(mocks.post).not.toHaveBeenCalled();
    expect(screen.getByText("Shared source → Destination 300")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Confirm delete rule" }));
    await waitFor(() => expect(mocks.post).toHaveBeenCalledWith(
      "/7/file/update-auto-settings?chatId=100", { archiveDeleteTargetChatId: "300" },
    ));
  });

  it("searches rules and pauses only the selected visible route", async () => {
    render(<CloudArchiveManager />);
    fireEvent.change(screen.getByRole("textbox", { name: "Search archive rules" }), { target: { value: "Destination 300" } });
    expect(screen.queryByText("Destination 200")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Select visible rules" }));
    fireEvent.click(screen.getByRole("button", { name: "Pause selected" }));
    await waitFor(() => expect(mocks.post).toHaveBeenCalledTimes(1));
    expect(mocks.post.mock.calls[0]?.[1]).toMatchObject({ archiveUpdate: { enabled: false, rule: { targetChatId: 300 } }, previousTargetChatId: "300" });
  });

  it("requires confirmation before deleting multiple selected routes", async () => {
    render(<CloudArchiveManager />);
    fireEvent.click(screen.getByRole("button", { name: "Select visible rules" }));
    fireEvent.click(screen.getByRole("button", { name: "Delete selected" }));
    expect(mocks.post).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Confirm delete rule" }));
    await waitFor(() => expect(mocks.post).toHaveBeenCalledTimes(2));
    expect(mocks.post.mock.calls.map(call => call[1].archiveDeleteTargetChatId)).toEqual(["200", "300"]);
  });

  it("starts history for the selected destination", async () => {
    render(<CloudArchiveManager />);
    fireEvent.click(screen.getAllByRole("button", { name: "Archive history" })[1]!);
    fireEvent.click(screen.getByRole("button", { name: "Start task" }));
    await waitFor(() => expect(mocks.post).toHaveBeenCalledTimes(1));
    expect(mocks.post).toHaveBeenCalledWith("/cloud-archive/history", {
      telegramId: "7", sourceChatId: "100", targetChatId: "300",
      scanMode: "ALL", maxMessages: 0, dailyLimit: 500,
    });
  });
});
