"use client";

import { useState } from "react";
import useSWR from "swr";
import { Button } from "@/components/ui/button";
import { useOptionalLanguage } from "@/i18n/language-provider";
import { useTelegramAccount } from "@/hooks/use-telegram-account";
import type { CloudArchiveOverview, CloudArchiveRuleOverview } from "@/lib/types";

type Day = { date: string; copied: number; forwarded: number; total: number; estimated: number };
type Statistics = { timeZone: string; days: Day[]; statistics?: { completed: number; pending: number; skipped: number; failed: number; unconfirmed: number } };

export function shanghaiToday(now = new Date()) {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(now);
  return ["year", "month", "day"].map(type => parts.find(part => part.type === type)?.value).join("-");
}

function previousDay(to: string, count: number) {
  const date = new Date(`${to}T00:00:00Z`); date.setUTCDate(date.getUTCDate() - count); return date.toISOString().slice(0, 10);
}

export function ArchiveDailyStatistics({ rules = [], compact = false }: { rules?: CloudArchiveRuleOverview[]; compact?: boolean }) {
  const language = useOptionalLanguage();
  const t = language?.t ?? ((text: string) => text);
  const { getAccounts } = useTelegramAccount();
  const accounts = getAccounts();
  const today = shanghaiToday();
  const [expanded, setExpanded] = useState(false);
  const { data: overview } = useSWR<CloudArchiveOverview>(expanded ? "/cloud-archive/overview" : null);
  const [account, setAccount] = useState("ALL");
  const [route, setRoute] = useState("ALL");
  const [range, setRange] = useState("7");
  const [from, setFrom] = useState(previousDay(today, 6));
  const [to, setTo] = useState(today);
  const scope = new URLSearchParams();
  if (account !== "ALL") scope.set("telegramId", account);
  if (route !== "ALL") {
    const [owner, source, target] = route.split(":");
    scope.set("telegramId", owner!); scope.set("sourceChatId", source!); scope.set("targetChatId", target!);
  }
  const todayQuery = new URLSearchParams(scope); todayQuery.set("from", today); todayQuery.set("to", today);
  const dailyQuery = new URLSearchParams(scope); dailyQuery.set("from", range === "custom" ? from : previousDay(today, Number(range) - 1)); dailyQuery.set("to", range === "custom" ? to : today);
  const validRange = from !== "" && to !== "" && to >= from && (new Date(to).getTime() - new Date(from).getTime()) / 86_400_000 <= 92;
  const { data: summary, error: todayError } = useSWR<Statistics>(`/cloud-archive/daily?${todayQuery}`, { refreshInterval: 15_000 });
  const { data, error } = useSWR<Statistics>(expanded && (range !== "custom" || validRange) ? `/cloud-archive/daily?${dailyQuery}` : null, { refreshInterval: 15_000 });
  const current = summary?.days?.[0];
  const count = (value?: number) => value == null ? "—" : value.toLocaleString();
  const selectedRules = (rules.length ? rules : overview?.rules ?? []).filter(rule => account === "ALL" || rule.telegramId === account);
  return <section className="rounded-xl border bg-card px-3 py-2.5" aria-label={t("Daily send statistics")}>
    <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 text-xs">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 tabular-nums">
        <strong>{t("Today")}</strong>
        <span>{t("Copied sends")} <strong>{count(current?.copied)}</strong></span>
        <span>{t("Forwards")} <strong>{count(current?.forwarded)}</strong></span>
        <span>{t("Successful total")} <strong>{count(current?.total)}</strong></span>
        {todayError && <span className="text-destructive">{t("Failed to load daily statistics")}</span>}
      </div>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-muted-foreground tabular-nums" aria-label={t("Cumulative overview")}>
        <span>{t("Cumulative successes")} <strong className="text-foreground">{count(summary?.statistics?.completed)}</strong></span>
        <span>{t("Queued")} <strong className="text-foreground">{count(summary?.statistics?.pending)}</strong></span>
        <span>{t("Skipped")} <strong className="text-foreground">{count(summary?.statistics?.skipped)}</strong></span>
        <span>{t("Failed")} <strong>{count(summary?.statistics?.failed)}</strong></span>
        <span>{t("Unconfirmed sends")} <strong>{count(summary?.statistics?.unconfirmed)}</strong></span>
      </div>
      <Button size="sm" variant="ghost" className="h-7 px-2 text-xs" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>{t(expanded ? "Hide statistics" : "View statistics")}</Button>
    </div>
    {expanded && <div className="mt-3 space-y-2 border-t pt-3">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <select className="h-8 max-w-full rounded border bg-background px-2" aria-label={t("Statistics account")} value={account} onChange={event => { setAccount(event.target.value); setRoute("ALL"); }}>
          <option value="ALL">{t("All accounts")}</option>{accounts.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
        </select>
        {!compact && <select className="h-8 max-w-full rounded border bg-background px-2" aria-label={t("Statistics destination")} value={route} onChange={event => setRoute(event.target.value)}>
          <option value="ALL">{t("All destinations")}</option>{selectedRules.map(rule => <option key={`${rule.telegramId}:${rule.sourceChatId}:${rule.targetChatId}`} value={`${rule.telegramId}:${rule.sourceChatId}:${rule.targetChatId}`}>{rule.sourceChatName} → {rule.targetChatName}</option>)}
        </select>}
        <select className="h-8 rounded border bg-background px-2" aria-label={t("Statistics date range")} value={range} onChange={event => setRange(event.target.value)}>
          <option value="7">{t("Last 7 days")}</option><option value="30">{t("Last 30 days")}</option><option value="custom">{t("Custom date range")}</option>
        </select>
        {range === "custom" && <><input className="h-8 rounded border bg-background px-2" type="date" aria-label={t("Start date")} value={from} onChange={event => setFrom(event.target.value)} /><span>—</span><input className="h-8 rounded border bg-background px-2" type="date" aria-label={t("End date")} value={to} onChange={event => setTo(event.target.value)} /></>}
        <span className="text-muted-foreground">{t("Beijing time · confirmed successful messages")}</span>
      </div>
      {range === "custom" && !validRange ? <p className="text-xs text-destructive">{t("Select a range of 1 to 93 days")}</p> : error ? <p className="text-xs text-destructive">{t("Failed to load daily statistics")}</p> :
        <div className="max-h-64 overflow-auto"><table className="w-full text-right text-xs tabular-nums"><thead className="sticky top-0 bg-card"><tr className="border-b"><th className="py-1.5 text-left">{t("Date")}</th><th>{t("Copied sends")}</th><th>{t("Forwards")}</th><th>{t("Successful total")}</th></tr></thead><tbody>
          {data?.days?.slice().reverse().map(day => <tr key={day.date} className="border-b last:border-0"><td className="py-1.5 text-left">{day.date}{day.estimated > 0 && <span className="ml-1 text-muted-foreground" title={t("Historical dates are estimated from old records")}>*</span>}</td><td>{count(day.copied)}</td><td>{count(day.forwarded)}</td><td className="font-medium">{count(day.total)}</td></tr>)}
          {!data?.days && <tr><td colSpan={4} className="py-3 text-center text-muted-foreground">{t("Loading statistics...")}</td></tr>}
        </tbody></table></div>}
      {data?.days?.some(day => day.estimated > 0) && <p className="text-xs text-muted-foreground">* {t("Historical dates are estimated from old records")}</p>}
      {route !== "ALL" && <p translate="no" className="truncate text-xs text-muted-foreground">{selectedRules.find(rule => `${rule.telegramId}:${rule.sourceChatId}:${rule.targetChatId}` === route)?.sourceChatName} → {selectedRules.find(rule => `${rule.telegramId}:${rule.sourceChatId}:${rule.targetChatId}` === route)?.targetChatName}</p>}
    </div>}
  </section>;
}
