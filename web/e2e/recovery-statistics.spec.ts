import type { Page } from "@playwright/test";
import { test, expect } from "./auth.fixture";
import { createDefaultAuto } from "../src/lib/automation";

async function fixture(page: Page) {
  const auto = createDefaultAuto();
  const calls: { path: string; body: Record<string, unknown> }[] = [];
  let recordStatus = "UNKNOWN";
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
    else if (path === "/settings") body = { proxys: { records: [] }, thumbnailAutoLoad: false };
    else if (path === "/cloud-archive/overview") body = { statistics: { total: 199565, pending: 31820, completed: 166034, failed: 1, unconfirmed: 1, skipped: 1702 }, rules: [{ ...auto.archive, enabled: true, rule: { ...auto.archive.rule, targetChatId: 200, strictOrder: true }, telegramId: "7", accountName: "测试账号", sourceChatId: "100", sourceChatName: "来源群", targetChatId: "200", targetChatName: "目标群", targetDownloadEnabled: false, syncStatus: "LIVE", syncTopicCount: 1, syncLastObservedMessageId: 57437847552, waitReason: recordStatus === "UNKNOWN" ? "UNCONFIRMED" : "QUEUED", queue: { pending: 31820, failed: 1, unconfirmed: recordStatus === "UNKNOWN" ? 1 : 0, firstRecordId: "blocked-1", firstUpdatedAt: Date.now() - 600000, lastSuccessAt: Date.now() - 600000, oldestPendingAt: Date.now() - 1200000 } }] };
    else if (path === "/cloud-archive/daily") body = { timeZone: "Asia/Shanghai", days: [{ date: url.searchParams.get("to"), copied: 12, forwarded: 3, total: 15, estimated: 0 }] };
    else if (path.endsWith("/diagnostics")) body = { record: { id: "blocked-1", telegramId: 7, sourceChatId: 100, sourceMessageId: 10, targetChatId: 200, status: recordStatus, updatedAt: Date.now() - 600000, lastErrorCode: "DELIVERY_UNCONFIRMED", lastErrorMessage: "Test timeout" }, attempts: [], audit: [] };
    else if (path.endsWith("/reconcile")) body = { status: "UNKNOWN", reason: "No saved delivery evidence; manual verification required" };
    else if (path.endsWith("/resolve")) { recordStatus = "SKIPPED"; body = { resolved: true }; }
    else if (path === "/notifications/bark") { if (request.method() === "POST") Object.assign(config, request.postDataJSON()); body = config; }
    else if (path === "/notifications/bark/test") body = { accepted: true };
    await route.fulfill({ headers, body: JSON.stringify(body) });
  });
  return calls;
}

test("Chinese home uses compact side-by-side account and connection panels within a desktop screen", async ({ page }) => {
  await fixture(page); await page.setViewportSize({ width: 1366, height: 768 }); await page.goto("/");
  await expect(page.getByRole("heading", { name: "账号管理", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "立即检查", exact: true })).toBeVisible();
  const columns = page.getByTestId("home-account-connection");
  const left = await columns.locator("section").boundingBox(); const right = await columns.locator("aside").boundingBox();
  expect(right!.x).toBeGreaterThan(left!.x + left!.width - 1);
  const overflow = await page.evaluate(() => document.documentElement.scrollHeight - window.innerHeight);
  expect(overflow).toBeLessThanOrEqual(2);
  const text = await page.locator("body").innerText(); expect(text).not.toMatch(/\b(active|Inactive|Dashboard|Recovery|Queued|Skipped)\b/);
  await page.screenshot({ path: "test-results/home-1.3.0-desktop.png", fullPage: true });
});

test("daily statistics show copy and forward counts and validate custom dates", async ({ page }) => {
  await fixture(page); await page.goto("/cloud-archive");
  await expect(page.getByRole("button", { name: "查看每日统计", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "查看每日统计", exact: true }).click();
  await expect(page.getByRole("columnheader", { name: "复制发送", exact: true })).toBeVisible();
  await expect(page.getByRole("columnheader", { name: "转发", exact: true })).toBeVisible();
  await page.getByLabel("统计目标", { exact: true }).selectOption("7:100:200");
  await page.getByLabel("统计日期范围", { exact: true }).selectOption("custom");
  await page.getByLabel("开始日期", { exact: true }).fill("2026-01-01");
  await page.getByLabel("结束日期", { exact: true }).fill("2026-12-31");
  await expect(page.getByText("请选择 1 至 93 天的日期范围", { exact: true })).toBeVisible();
  const text = await page.locator("body").innerText(); expect(text).not.toMatch(/\b(Recovery|Strict order|checkpoint|Disabled|Queued|Skipped|UNCONFIRMED)\b/);
});

test("blocked delivery can be verified and explicitly skipped with an audit reason", async ({ page, csrfToken }) => {
  const calls = await fixture(page); await page.goto("/cloud-archive?record=blocked-1");
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("button", { name: "立即核实", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("没有已保存的发送证据");
  await expect(page.getByRole("button", { name: "跳过并继续", exact: true })).toBeDisabled();
  await page.getByLabel("处理原因", { exact: true }).fill("已核对目标，决定跳过这条消息");
  await page.getByRole("button", { name: "跳过并继续", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("记录已处理");
  expect(calls.find(call => call.path.endsWith("/resolve"))?.body).toMatchObject({ action: "SKIP", reason: "已核对目标，决定跳过这条消息" });
  expect(csrfToken).toBeTruthy();
});

test("notification settings mask the saved device key and can submit a test notification", async ({ page, csrfToken }) => {
  const calls = await fixture(page); await page.goto("/");
  await page.getByRole("button", { name: "打开设置", exact: true }).click();
  await page.getByRole("tab", { name: "通知", exact: true }).click();
  const key = page.getByLabel("设备密钥", { exact: true }); await expect(key).toHaveValue(""); await expect(key).toHaveAttribute("type", "password");
  await key.fill("new-test-device-key");
  await page.getByRole("button", { name: "保存并发送测试通知", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("测试通知已提交");
  expect(calls.some(call => call.path === "/notifications/bark/test")).toBe(true);
  expect(csrfToken).toBeTruthy();
});

test("mobile home keeps controls reachable without horizontal overflow", async ({ page }) => {
  await fixture(page); await page.setViewportSize({ width: 390, height: 844 }); await page.goto("/");
  await expect(page.getByRole("button", { name: "代理设置", exact: true })).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth); expect(overflow).toBeLessThanOrEqual(1);
  await page.screenshot({ path: "test-results/home-1.3.0-mobile.png", fullPage: true });
});
