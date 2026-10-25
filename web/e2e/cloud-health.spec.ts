import type { Page } from "@playwright/test";
import { test, expect } from "./auth.fixture";
import { createDefaultAuto } from "../src/lib/automation";

async function installApi(page: Page, locale = "en") {
  const auto = createDefaultAuto();
  let targets = ["200", "300"];
  let health = "HEALTHY";
  const deleted: string[] = [];
  await page.addInitScript(locale => localStorage.setItem("telegram-files:locale", locale), locale);
  await page.routeWebSocket("ws://localhost:8080/**", () => {});
  await page.route("http://localhost:8080/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const headers = { "access-control-allow-credentials": "true", "access-control-allow-headers": "content-type,x-csrf-token", "access-control-allow-methods": "GET,POST,OPTIONS", "access-control-allow-origin": "http://localhost:3000", "content-type": "application/json" };
    if (request.method() === "OPTIONS") { await route.fulfill({ status: 204, headers }); return; }
    let body: unknown = {};
    if (path === "/auth/bootstrap/status") body = { required: false };
    else if (path === "/auth/session") body = { authenticated: true, username: "admin", idleExpiresAt: Date.now() + 60000, absoluteExpiresAt: Date.now() + 120000 };
    else if (path === "/telegrams") body = [{ id: "7", name: "Account A", phoneNumber: "", status: "active", proxy: "Office SOCKS5", rootPath: "/fixture" }];
    else if (path === "/telegram/health") body = [{ telegramId: "7", accountName: "Account A", proxy: "Office SOCKS5", status: health, connectionState: health === "HEALTHY" ? "ready" : "connectingToProxy", probeStatus: health === "HEALTHY" ? "OK" : "FAILED", checkedAt: Date.now(), lastSuccessAt: Date.now() - 60000, unhealthySince: health === "ERROR" ? Date.now() - 90000 : 0, latencyMs: health === "HEALTHY" ? 126 : null }];
    else if (path.endsWith("/check-health")) { health = "HEALTHY"; }
    else if (path === "/cloud-archive/overview") body = { statistics: { total: 0, pending: 0, completed: 0, failed: 0, skipped: 0 }, rules: targets.map(target => ({ telegramId: "7", accountName: "Account A", sourceChatId: "100", sourceChatName: "Shared source", targetChatId: target, targetChatName: `Destination ${target}`, enabled: true, targetDownloadEnabled: true, rule: { ...auto.archive.rule, targetChatId: Number(target) }, syncStatus: "LIVE", waitReason: "LIVE", queue: { pending: 2, failed: 0, lastSuccessAt: Date.now() - 10000, oldestPendingAt: Date.now() - 5000 } })) };
    else if (path.endsWith("/update-auto-settings")) { const target = String(request.postDataJSON().archiveDeleteTargetChatId); deleted.push(target); targets = targets.filter(item => item !== target); }
    else if (path === "/cloud-archive/history" || path === "/cloud-archive/records") body = [];
    else if (path === "/files/count") body = { downloading: 0, completed: 0, downloadedSize: 0 };
    await route.fulfill({ status: 200, headers, body: JSON.stringify(body) });
  });
  return { deleted, fail: () => { health = "ERROR"; } };
}

test("home reports a proxy outage, recovers after a manual check and fits mobile", async ({ page, csrfToken }, info) => {
  void csrfToken;
  const api = await installApi(page);
  await page.goto("/");
  await expect(page.getByTestId("account-cards")).toContainText("Office SOCKS5");
  await expect(page.getByText(/126 Milliseconds/)).toBeVisible();
  api.fail();
  await page.reload();
  await expect(page.getByRole("alert").filter({ hasText: "Telegram connection unavailable" })).toBeVisible();
  await page.screenshot({ path: info.outputPath("home-outage.png"), fullPage: true });
  await page.getByRole("button", { name: "Check now" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "Telegram connection unavailable" })).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: info.outputPath("home-mobile.png"), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test("deletes only the selected destination after confirmation", async ({ page, csrfToken }, info) => {
  void csrfToken;
  const api = await installApi(page);
  await page.goto("/cloud-archive");
  await page.getByRole("button", { name: "Delete rule", exact: true }).nth(1).click();
  await expect(page.getByRole("dialog")).toContainText("Destination 300");
  expect(api.deleted).toEqual([]);
  await page.screenshot({ path: info.outputPath("delete-dialog.png"), fullPage: true });
  await page.getByRole("button", { name: "Confirm delete rule" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(api.deleted).toEqual(["300"]);
  await expect(page.getByText("Destination 200", { exact: true })).toBeVisible();
  await expect(page.getByText("Destination 300", { exact: true })).toHaveCount(0);
});

// Verify the existing DOM translation layer also covers the new product flows.
test("Chinese health panel and deletion confirmation are localized", async ({ page, csrfToken }) => {
  void csrfToken;
  await installApi(page, "zh-CN");
  await page.goto("/cloud-archive");
  await page.getByRole("button", { name: "删除规则", exact: true }).nth(1).click();
  await expect(page.getByRole("dialog")).toContainText("保留目标频道已有消息和成功记录");
  await page.getByRole("button", { name: "确认删除规则", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.goto("/");
  await expect(page.getByTestId("account-cards")).toContainText("当前代理");
  await expect(page.getByRole("button", { name: "立即检查", exact: true })).toBeVisible();
});
