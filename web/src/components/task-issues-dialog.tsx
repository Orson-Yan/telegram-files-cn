"use client";

import { useRef, useState } from "react";
import useSWR from "swr";
import { useVirtualizer } from "@tanstack/react-virtual";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from "@/components/ui/dialog";
import { useOptionalLanguage } from "@/i18n/language-provider";
import { POST, request } from "@/lib/api";
import type { CloudArchiveRecord, CloudArchiveRuleOverview } from "@/lib/types";

type Scope = { telegramId: string; sourceChatId: string; targetChatId: string };
type Issue = { id: string; sourceMessageId: string; status: string; updatedAt: number; errorCode?: string };
type Page = { items: Issue[]; total: number; nextCursor: string | null };
type Issues = { scope: Scope; items: Issue[] };

async function loadIssues(_key: string, task: Scope | null, recordId: string | null): Promise<Issues> {
  let scope = task;
  if (!scope && recordId) {
    const diagnostic = await request<{ record: CloudArchiveRecord }>(`/cloud-archive/records/${encodeURIComponent(recordId)}/diagnostics`);
    scope = { telegramId: String(diagnostic.record.telegramId), sourceChatId: String(diagnostic.record.sourceChatId), targetChatId: String(diagnostic.record.targetChatId) };
  }
  if (!scope) throw new Error("A task scope is required");
  const items: Issue[] = [];
  let cursor: string | null = "0";
  while (cursor !== null) {
    const query = new URLSearchParams({ ...scope, cursor, limit: "200" });
    const page: Page = await request<Page>(`/cloud-archive/issues?${query}`);
    items.push(...page.items);
    if (page.nextCursor !== null && BigInt(page.nextCursor) <= BigInt(cursor)) throw new Error("Issue list could not be loaded");
    cursor = page.nextCursor;
  }
  return { scope, items };
}

export function TaskIssuesDialog({ task, recordId, onClose, onChanged }: { task: CloudArchiveRuleOverview | null; recordId: string | null; onClose: () => void; onChanged: () => void }) {
  const t = useOptionalLanguage()?.t ?? ((text: string) => text);
  const open = !!task || !!recordId;
  const key = open ? `/task-issues/${task ? `${task.telegramId}:${task.sourceChatId}:${task.targetChatId}` : recordId}` : null;
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [notice, setNotice] = useState("");
  const { data, error, isLoading, mutate } = useSWR<Issues>(key, current => loadIssues(current, task, recordId), { refreshInterval: busy ? 0 : 10_000 });
  const scroll = useRef<HTMLDivElement>(null);
  const items = data?.items ?? [];
  const eligible = items.filter(item => selected.has(item.id));
  const virtual = useVirtualizer({ count: items.length, getScrollElement: () => scroll.current, estimateSize: () => 58, overscan: 8 });
  const allSelected = items.length > 0 && eligible.length === items.length;
  async function skip(ids: string[]) {
    if (!data || !ids.length || busy) return;
    setBusy(true); setNotice("");
    let skipped = 0;
    const done = new Set<string>();
    try {
      for (let offset = 0; offset < ids.length; offset += 200) {
        const result = await POST("/cloud-archive/issues/skip", { ...data.scope, ids: ids.slice(offset, offset + 200) }) as { results: { id: string; result: string }[]; skipped: number };
        skipped += result.skipped;
        result.results.filter(item => ["SKIPPED", "CHANGED"].includes(item.result)).forEach(item => done.add(item.id));
      }
      setNotice(`${t("Skipped")}: ${skipped} · ${t("Not skipped")}: ${ids.length - skipped}`);
    } catch { setNotice(`${t("Skipped")}: ${skipped} · ${t("Some issues could not be processed; unprocessed rows remain selected")}`); }
    finally {
      setSelected(previous => new Set([...previous].filter(id => !done.has(id))));
      try { await mutate(); } catch { /* SWR exposes refresh failures in the dialog. */ }
      finally { onChanged(); setBusy(false); }
    }
  }
  function close() { if (!busy) { setSelected(new Set()); setNotice(""); onClose(); } }
  const reason = (code?: string) => t(code === "PARTIAL_ALBUM_DELIVERY" ? "Album partly delivered" : code === "MAX_RETRIES_EXCEEDED" ? "Automatic retries exhausted" : code === "PROCESS_RESTARTED" ? "Send interrupted by restart" : code === "DELIVERY_UNCONFIRMED" ? "Delivery confirmation unavailable" : "Delivery failed; needs attention");
  return <Dialog open={open} onOpenChange={next => { if (!next) close(); }}><DialogContent className="max-h-[85dvh] overflow-hidden sm:max-w-3xl">
    <DialogHeader><DialogTitle>{t("Task issues")} · {items.length}</DialogTitle><DialogDescription>{t("Recoverable failures retry automatically. Select messages you want to skip; later messages continue once the blocking issues are resolved.")}</DialogDescription></DialogHeader>
    {task && <p translate="no" className="truncate text-sm">{task.sourceChatName} → {task.targetChatName}</p>}
    <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
      <label className="flex items-center gap-2"><input type="checkbox" aria-label={t("Select all task issues")} disabled={busy || !items.length || isLoading} checked={allSelected} onChange={event => setSelected(event.target.checked ? new Set(items.map(item => item.id)) : new Set())} />{t("Select all")} · {t("Selected")}: {eligible.length}</label>
      <Button size="sm" variant="destructive" disabled={busy || !eligible.length} onClick={() => void skip(eligible.map(item => item.id))}>{t(busy ? "Processing..." : "Skip selected")}</Button>
    </div>
    {error && <p role="alert" className="text-sm text-destructive">{t("Issue list could not be loaded")}</p>}
    {isLoading && <p className="py-4 text-center text-sm">{t("Loading all task issues...")}</p>}
    {!isLoading && !error && !items.length && <p className="py-6 text-center text-sm text-muted-foreground">{t("No unresolved issues in this task")}</p>}
    {!!items.length && <div ref={scroll} className="max-h-[50dvh] min-h-14 overflow-y-auto rounded border" style={{ height: Math.min(items.length * 58, 420) }}>
      <div role="list" aria-label={t("All task issues")} style={{ height: virtual.getTotalSize(), position: "relative" }}>
        {virtual.getVirtualItems().map(row => { const item = items[row.index]!; return <div role="listitem" key={item.id} data-index={row.index} ref={virtual.measureElement} className="absolute left-0 top-0 flex w-full items-center gap-3 border-b px-3 py-2 text-xs" style={{ transform: `translateY(${row.start}px)` }}>
          <input type="checkbox" aria-label={`${t("Source message")} ${item.sourceMessageId}`} disabled={busy} checked={selected.has(item.id)} onChange={event => setSelected(previous => { const next = new Set(previous); if (event.target.checked) next.add(item.id); else next.delete(item.id); return next; })} />
          <div className="min-w-0 flex-1"><div className="flex flex-wrap gap-x-3"><span className="font-medium tabular-nums">{item.sourceMessageId}</span><span className="text-destructive">{t(item.status === "UNKNOWN" ? "Unconfirmed sends" : "Failed")}</span></div><div className="mt-1 truncate text-muted-foreground">{reason(item.errorCode)} · {new Date(item.updatedAt).toLocaleString()}</div></div>
          <Button size="sm" variant="ghost" className="h-7 shrink-0 px-2 text-xs" disabled={busy} onClick={() => void skip([item.id])}>{t("Skip directly")}</Button>
        </div>; })}
      </div>
    </div>}
    {notice && <p role="status" className="text-xs">{notice} · {t("Remaining issues")}: {items.length}</p>}
  </DialogContent></Dialog>;
}
