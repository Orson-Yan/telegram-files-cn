import type { Page } from "@playwright/test";
import { test, expect } from "./auth.fixture";
import { createDefaultAuto } from "../src/lib/automation";

async function fixture(page: Page, count = 7) {
  const auto = createDefaultAuto();
  const calls: { path: string; body: Record<string, unknown> }[] = [];
  let issues = Array.from({ length: count }, (_, index) => ({ id: `blocked-${index + 1}`, sourceMessageId: String(index + 10), status: index === count - 1 ? "FAILED" : "UNKNOWN", updatedAt: Date.now() - 600000, errorCode: "DELIVERY_UNCONFIRMED" }));
  const config = { enabled: false, serverUrl: "https://api.day.app", siteUrl: "", keyConfigured: true, unconfirmedMinutes: 5, connectionMinutes: 2, stalledMinutes: 15, repeatMinutes: 0, recovery: true, events: ["UNCONFIRMED", "FAILED", "CONNECTION", "LOGIN", "HISTORY", "STALLED"], incidents: [] };
  await page.addInitScript(() => localStorage.setItem("telegram-files:locale", "zh-CN"));
  await page.routeWebSocket("ws://localhost:8080/**", () => {});
  await page.route("http://localhost:8080/**", async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname;
    const headers = { "access-control-allow-credentials": "true", "access-control-allow-headers": "content-type,x-csrf-token", "access-control-allow-methods": "GET,POST,OPTIONS", "access-control-allow-origin": "http://localhost:3000", "content-type": "application/json" };
    if (request.method() === "OPTIONS") { await route.fulfill({ status: 204, headers }); return; }
    let body: unknown = [];
    if (request.method() === "POST") calls.push({ path, body: request.postDataJSON() as Record<string, unknown> });
    if (path === "/auth/bootstrap/status") body = { required: false };
    else if (path === "/auth/session") body = { authenticated: true, username: "管理员", idleExpiresAt: Date.now() + 60000, absoluteExpiresAt: Date.now() + 120000 };
    else if (path === "/telegrams") body = [{ id: "7", name: "测试账号", phoneNumber: "", status: "active", proxy: "办公室代理", rootPath: "/fixture" }];
    else if (path === "/telegram/health") body = [{ telegramId: "7", accountName: "测试账号", proxy: "办公室代理", status: "HEALTHY", connectionState: "ready", probeStatus: "OK", checkedAt: Date.now(), lastSuccessAt: Date.now(), unhealthySince: 0, latencyMs: 141 }];
    else if (path === "/files/count") body = { completed: 128, downloading: 0, waiting: 0, failed: 0, downloadedSize: 0, downloadLimit: 3 };
    else if (path === "/settings") body = { proxys: JSON.stringify({ items: [{ name: "办公室代理", server: "127.0.0.1", port: 1080, type: "socks5" }] }), thumbnailAutoLoad: false };
    else if (path === "/cloud-archive/overview") body = { statistics: { total: 199565, pending: 31820, completed: 166034, failed: 1, unconfirmed: 1, skipped: 1702 }, rules: [{ ...auto.archive, enabled: true, rule: { ...auto.archive.rule, targetChatId: 200, strictOrder: true }, telegramId: "7", accountName: "测试账号", sourceChatId: "100", sourceChatName: "来源群", targetChatId: "200", targetChatName: "目标群", targetDownloadEnabled: false, syncStatus: "LIVE", syncTopicCount: 1, syncLastObservedMessageId: 57437847552, waitReason: issues.length ? "UNCONFIRMED" : "QUEUED", queue: { completed: 166034, todayCompleted: 15, pending: 31820, failed: issues.filter(item => item.status === "FAILED").length, unconfirmed: issues.filter(item => item.status === "UNKNOWN").length, firstRecordId: "blocked-1", firstUpdatedAt: Date.now() - 600000, lastSuccessAt: Date.now() - 600000, oldestPendingAt: Date.now() - 1200000 } }] };
    else if (path === "/cloud-archive/daily") body = { timeZone: "Asia/Shanghai", statistics: { completed: 166034, pending: 31820, skipped: 1702, failed: 1, unconfirmed: issues.length }, days: [{ date: url.searchParams.get("to"), copied: 12, forwarded: 3, total: 15, estimated: 0 }] };
    else if (path.endsWith("/diagnostics")) body = { record: { id: "blocked-1", telegramId: 7, sourceChatId: 100, sourceMessageId: 10, targetChatId: 200, status: "UNKNOWN", updatedAt: Date.now() - 600000, lastErrorCode: "DELIVERY_UNCONFIRMED", lastErrorMessage: "Test timeout" }, attempts: [], audit: [] };
    else if (path.endsWith("/reconcile")) body = { status: "UNKNOWN", reason: "No saved delivery evidence; manual verification required" };
    else if (path === "/cloud-archive/issues") {
      const after = Number(url.searchParams.get("cursor") ?? 0);
      const items = issues.filter(item => Number(item.sourceMessageId) > after).slice(0, 200);
      body = { items, total: issues.length, nextCursor: items.length === 200 ? items.at(-1)!.sourceMessageId : null };
    }
    else if (path === "/cloud-archive/issues/skip") {
      const ids = (request.postDataJSON() as { ids: string[] }).ids;
      issues = issues.filter(item => !ids.includes(item.id));
      body = { results: ids.map(id => ({ id, result: "SKIPPED" })), skipped: ids.length };
    }
    else if (path === "/notifications/bark") { if (request.method() === "POST") Object.assign(config, request.postDataJSON()); body = config; }
    else if (path === "/notifications/bark/test") body = { accepted: true };
    await route.fulfill({ headers, body: JSON.stringify(body) });
  });
  return { calls, addIssue: () => issues.push({ id: "new-issue", sourceMessageId: "9999", status: "UNKNOWN", updatedAt: Date.now(), errorCode: "TIMEOUT" }) };
}

test("Chinese home combines each account and its connection in a compact card within one desktop screen", async ({ page }) => {
  await fixture(page); await page.setViewportSize({ width: 1366, height: 768 }); await page.goto("/");
  await expect(page.getByRole("heading", { name: "账号管理", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "立即检查", exact: true })).toBeVisible();
  const cards = page.getByTestId("account-cards");
  const card = await cards.locator(":scope > div").first().boundingBox();
  expect(card!.width).toBeLessThanOrEqual(320);
  await expect(cards).toContainText("办公室代理");
  await expect(cards).toContainText("141 毫秒");
  const overflow = await page.evaluate(() => document.documentElement.scrollHeight - window.innerHeight);
  expect(overflow).toBeLessThanOrEqual(2);
  const text = await page.locator("body").innerText(); expect(text).not.toMatch(/\b(active|Inactive|Dashboard|Recovery|Queued|Skipped)\b/);
  await page.screenshot({ path: "test-results/home-1.3.1-desktop.png", fullPage: true });
});

test("daily statistics show copy and forward counts and validate custom dates", async ({ page }) => {
  await fixture(page); await page.goto("/");
  await expect(page.getByRole("button", { name: "查看统计", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "查看统计", exact: true }).click();
  await expect(page.getByRole("columnheader", { name: "复制发送", exact: true })).toBeVisible();
  await expect(page.getByRole("columnheader", { name: "转发", exact: true })).toBeVisible();
  await page.getByLabel("统计目标", { exact: true }).selectOption("7:100:200");
  await page.getByLabel("统计日期范围", { exact: true }).selectOption("custom");
  await page.getByLabel("开始日期", { exact: true }).fill("2026-01-01");
  await page.getByLabel("结束日期", { exact: true }).fill("2026-12-31");
  await expect(page.getByText("请选择 1 至 93 天的日期范围", { exact: true })).toBeVisible();
  const text = await page.locator("body").innerText(); expect(text).not.toMatch(/\b(Recovery|Strict order|checkpoint|Disabled|Queued|Skipped|UNCONFIRMED)\b/);
});

test("task issues list all seven messages and skip only explicitly selected messages", async ({ page, csrfToken }) => {
  const { calls } = await fixture(page); await page.goto("/cloud-archive?record=blocked-1");
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("heading")).toHaveText("任务异常 · 7");
  await expect(dialog.getByRole("listitem")).toHaveCount(7);
  await expect(dialog.getByRole("textbox")).toHaveCount(0);
  await expect(dialog.getByRole("button", { name: /重试|核实|确认已发送/ })).toHaveCount(0);
  await dialog.getByRole("checkbox", { name: "来源消息 10", exact: true }).check();
  await dialog.getByRole("checkbox", { name: "来源消息 12", exact: true }).check();
  await dialog.getByRole("button", { name: "跳过所选", exact: true }).click();
  await expect(dialog.getByRole("heading")).toHaveText("任务异常 · 5");
  expect(calls.filter(call => call.path === "/cloud-archive/issues/skip").map(call => call.body)).toEqual([{ telegramId: "7", sourceChatId: "100", targetChatId: "200", ids: ["blocked-1", "blocked-3"] }]);
  expect(calls.some(call => /resolve|retry/.test(call.path))).toBe(false);
  expect(csrfToken).toBeTruthy();
});

test("all 205 issues load across pages and batch skip in bounded requests", async ({ page, csrfToken }) => {
  void csrfToken;
  const { calls } = await fixture(page, 205); await page.goto("/cloud-archive");
  await expect(page.getByRole("button", { name: "查看统计", exact: true })).toHaveCount(0);
  await expect(page.getByText("今日成功", { exact: false })).toBeVisible();
  await page.getByRole("button", { name: "处理异常 205", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("heading")).toHaveText("任务异常 · 205");
  expect(await dialog.getByRole("listitem").count()).toBeLessThan(30);
  await dialog.getByRole("checkbox", { name: "选择该任务全部异常", exact: true }).check();
  await dialog.getByRole("button", { name: "跳过所选", exact: true }).click();
  await expect(dialog.getByRole("heading")).toHaveText("任务异常 · 0");
  expect(calls.filter(call => call.path === "/cloud-archive/issues/skip").map(call => (call.body.ids as string[]).length)).toEqual([200, 5]);
});

test("newly discovered issues are not silently added to an existing selection", async ({ page, csrfToken }) => {
  void csrfToken;
  const api = await fixture(page); await page.goto("/cloud-archive?record=blocked-1");
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("heading")).toHaveText("任务异常 · 7");
  await dialog.getByRole("checkbox", { name: "选择该任务全部异常", exact: true }).check();
  api.addIssue();
  await expect(dialog.getByRole("heading")).toHaveText("任务异常 · 8", { timeout: 15000 });
  await dialog.getByRole("button", { name: "跳过所选", exact: true }).click();
  await expect(dialog.getByRole("heading")).toHaveText("任务异常 · 1");
  expect(api.calls.find(call => call.path === "/cloud-archive/issues/skip")?.body.ids).not.toContain("new-issue");
});

test("a failed second batch keeps the remaining five selected for another attempt", async ({ page, csrfToken }) => {
  void csrfToken;
  const { calls } = await fixture(page, 205);
  let requests = 0;
  await page.route("http://localhost:8080/cloud-archive/issues/skip", async route => {
    if (route.request().method() === "POST" && ++requests === 2) {
      await route.fulfill({ status: 503, headers: { "access-control-allow-origin": "http://localhost:3000", "access-control-allow-credentials": "true" }, body: "temporary test failure" });
    } else await route.fallback();
  });
  await page.goto("/cloud-archive?record=blocked-1");
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("heading")).toHaveText("任务异常 · 205");
  await dialog.getByRole("checkbox", { name: "选择该任务全部异常", exact: true }).check();
  await dialog.getByRole("button", { name: "跳过所选", exact: true }).click();
  await expect(dialog.getByRole("heading")).toHaveText("任务异常 · 5");
  await expect(dialog.getByRole("button", { name: "跳过所选", exact: true })).toBeEnabled();
  await dialog.getByRole("button", { name: "跳过所选", exact: true }).click();
  await expect(dialog.getByRole("heading")).toHaveText("任务异常 · 0");
  expect(calls.filter(call => call.path === "/cloud-archive/issues/skip").map(call => (call.body.ids as string[]).length)).toEqual([200, 5]);
});

test("notification settings mask the saved device key and can submit a test notification", async ({ page, csrfToken }) => {
  const { calls } = await fixture(page); await page.goto("/");
  await page.getByRole("button", { name: "打开设置", exact: true }).click();
  await page.getByRole("tab", { name: "通知", exact: true }).click();
  const key = page.getByLabel("设备密钥", { exact: true }); await expect(key).toHaveValue(""); await expect(key).toHaveAttribute("type", "password");
  await key.fill("new-test-device-key");
  await page.getByRole("button", { name: "保存并发送测试通知", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("测试通知已提交");
  expect(calls.some(call => call.path === "/notifications/bark/test")).toBe(true);
  expect(csrfToken).toBeTruthy();
});

test("mobile home keeps controls reachable without horizontal overflow", async ({ page, csrfToken }) => {
  void csrfToken;
  await fixture(page); await page.setViewportSize({ width: 390, height: 844 }); await page.goto("/");
  await expect(page.getByRole("button", { name: "账号选项", exact: true })).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth); expect(overflow).toBeLessThanOrEqual(1);
  await page.screenshot({ path: "test-results/home-1.3.1-mobile.png", fullPage: true });
  await page.getByRole("button", { name: "账号选项", exact: true }).click();
  await page.getByRole("menuitem", { name: "更换代理", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("heading", { name: "代理设置", exact: true })).toBeVisible();
  await dialog.getByRole("button", { name: "应用代理", exact: true }).click();
  await expect(dialog).toHaveCount(0);
});
