import type { Page } from "@playwright/test";
import { expect, test } from "./auth.fixture";

async function fixture(page: Page) {
  const calls: { path: string; body: Record<string, unknown> }[] = [];
  const searches: string[] = [];
  const tasks = [{ id: "7:100", account_id: "7", chat_id: "100", title: "已有频道", rule_json: '{"fileTypes":[],"downloadHistory":true}', enabled: 1, paused: 0, initialized: 1, history_done: 1, scanned_count: 100, last_check: Date.now(), next_scan_at: 0, counts: { COMPLETED: 8, RETRY: 1 }, bytes: {} }];
  await page.addInitScript(() => localStorage.setItem("telegram-files:locale", "zh-CN"));
  await page.routeWebSocket("ws://localhost:8080/**", () => {});
  await page.route("http://localhost:8080/**", async route => {
    const req = route.request(); const url = new URL(req.url()); const path = url.pathname;
    const headers = { "access-control-allow-credentials": "true", "access-control-allow-headers": "content-type,x-csrf-token", "access-control-allow-methods": "GET,POST,OPTIONS", "access-control-allow-origin": "http://localhost:3000", "content-type": "application/json" };
    if (req.method() === "OPTIONS") { await route.fulfill({ status: 204, headers }); return; }
    let body: unknown = [];
    if (req.method() === "POST") calls.push({ path, body: req.postDataJSON() as Record<string, unknown> });
    if (path === "/auth/bootstrap/status") body = { required: false };
    else if (path === "/auth/session") body = { authenticated: true, username: "管理员", idleExpiresAt: Date.now() + 60000, absoluteExpiresAt: Date.now() + 120000 };
    else if (path === "/settings") body = {};
    else if (path === "/telegrams") body = [{ id: "7", name: "测试账号", phoneNumber: "", status: "active", rootPath: "/fixture" }];
    else if (path === "/files/count") body = { completed: 8, downloading: 0, downloadedSize: 0, downloadLimit: 5 };
    else if (path === "/files") body = { files: [], count: 0, nextFromMessageId: 0 };
    else if (path === "/channel-downloads" && req.method() === "GET") body = tasks;
    else if (path === "/channel-downloads" && req.method() === "POST") body = { items: [{ chatId: "999", title: "归档 & 新频道", created: true }] };
    else if (path === "/telegram/7/chats") {
      searches.push(url.searchParams.get("query") ?? "");
      body = [{ id: "999", name: "归档 & 新频道", type: "channel" }];
    } else if (path.endsWith("/preview")) body = { items: [{ name: "lesson.mp4", category: "video", matched: true }, { name: "book.pdf", category: "ebook", matched: false }], sampleOnly: true };
    else if (path.endsWith("/pause")) { tasks[0]!.paused = 1; body = { ok: true }; }
    else if (path.endsWith("/resume")) { tasks[0]!.paused = 0; body = { ok: true }; }
    await route.fulfill({ status: 200, headers, body: JSON.stringify(body) });
  });
  return { calls, searches };
}

test("creates a historical and live channel download with a video content preset", async ({ page, csrfToken }, info) => {
  void csrfToken;
  const api = await fixture(page);
  await page.goto("/downloads");
  await page.getByRole("button", { name: "新建频道任务" }).click();
  await page.getByLabel("搜索下载频道").fill("归档 & 新频道");
  await expect.poll(() => api.searches.includes("归档 & 新频道")).toBe(true);
  await page.getByLabel("归档 & 新频道", { exact: true }).check();
  await page.getByLabel("规则模板").selectOption("video");
  await page.getByRole("button", { name: "预览匹配样本" }).click();
  await expect(page.getByRole("dialog")).toContainText("lesson.mp4");
  await expect(page.getByRole("dialog")).toContainText("不符合当前筛选");
  await page.screenshot({ path: info.outputPath("channel-create-desktop.png"), fullPage: true });
  await page.getByRole("button", { name: "创建并开始下载" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  const created = api.calls.find(call => call.path === "/channel-downloads")!;
  expect(created.body.accountId).toBe("7");
  expect(created.body.chatIds).toEqual(["999"]);
  expect(created.body.rule).toMatchObject({ downloadHistory: true, fileTypes: ["video"] });
});

test("pauses and resumes only the selected channel task", async ({ page, csrfToken }) => {
  void csrfToken;
  const api = await fixture(page);
  await page.goto("/downloads");
  await page.getByRole("button", { name: "暂停派发", exact: true }).click();
  await expect(page.getByText("已暂停派发", { exact: true })).toBeVisible();
  expect(api.calls.some(call => call.path === "/channel-downloads/7%3A100/pause")).toBe(true);
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await expect(page.getByText("已暂停派发", { exact: true })).toHaveCount(0);
});

test("channel task creation fits a narrow mobile screen", async ({ page, csrfToken }, info) => {
  void csrfToken;
  await fixture(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/downloads");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole("button", { name: "新建频道任务" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  expect(await page.getByRole("dialog").evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
  await page.screenshot({ path: info.outputPath("channel-create-mobile.png"), fullPage: true });
});
