"use client";

import { useState } from "react";
import useSWR, { useSWRConfig } from "swr";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from "@/components/ui/dialog";
import { useOptionalLanguage } from "@/i18n/language-provider";
import { POST } from "@/lib/api";
import type { CloudArchiveRecord } from "@/lib/types";

type Diagnostics = { record: CloudArchiveRecord; attempts: { id: string; state: string; started_at: number }[]; audit: { id: string; action: string; reason: string; created_at: number }[] };

export function ArchiveRecoveryDialog({ recordId, onClose, onChanged }: { recordId: string | null; onClose: () => void; onChanged: () => void }) {
  const t = useOptionalLanguage()?.t ?? ((text: string) => text);
  const { mutate } = useSWRConfig();
  const { data, error, mutate: reload } = useSWR<Diagnostics>(recordId ? `/cloud-archive/records/${encodeURIComponent(recordId)}/diagnostics` : null);
  const [reason, setReason] = useState("");
  const [target, setTarget] = useState("");
  const [busy, setBusy] = useState(false);
  const [checkedMissing, setCheckedMissing] = useState(false);
  const [notice, setNotice] = useState("");
  const record = data?.record;
  const editable = record && ["UNKNOWN", "FAILED"].includes(record.status);
  async function act(action: "RECONCILE" | "CONFIRM_SENT" | "SKIP" | "RETRY") {
    if (!recordId) return;
    setBusy(true); setNotice("");
    try {
      if (action === "RECONCILE") {
        const result = await POST(`/cloud-archive/records/${encodeURIComponent(recordId)}/reconcile`) as { status: string; reason?: string };
        setNotice(result.status === "COMPLETED" ? t("Delivery verified; the queue can continue") : t(result.reason || "The result still requires manual verification"));
      } else if (action === "RETRY") {
        await POST(`/cloud-archive/records/${encodeURIComponent(recordId)}/retry`, { confirmUncertain: checkedMissing, reason: reason.trim() });
        setNotice(t("Retry queued"));
      } else {
        await POST(`/cloud-archive/records/${encodeURIComponent(recordId)}/resolve`, { action, reason: reason.trim(), targetMessage: target.trim() });
        setNotice(t("Record resolved; the queue can continue"));
      }
      await reload(); onChanged();
      void mutate(key => typeof key === "string" && key.startsWith("/cloud-archive/daily"));
    } catch (failure) { setNotice(t(failure instanceof Error ? failure.message : "Operation failed")); }
    finally { setBusy(false); }
  }
  const close = () => { if (!busy) { setReason(""); setTarget(""); setNotice(""); setCheckedMissing(false); onClose(); } };
  return <Dialog open={!!recordId} onOpenChange={open => { if (!open) close(); }}><DialogContent className="max-h-[85dvh] overflow-auto sm:max-w-xl">
    <DialogHeader><DialogTitle>{t("Resolve blocked delivery")}</DialogTitle><DialogDescription>{t("Verify the destination before retrying or skipping to avoid duplicates or missing messages.")}</DialogDescription></DialogHeader>
    {error && <p className="text-sm text-destructive">{t("Failed to load delivery diagnostics")}</p>}
    {!record && !error && <p>{t("Loading...")}</p>}
    {record && <div className="space-y-3 text-sm">
      <div className="rounded-lg bg-muted/50 p-3 text-xs tabular-nums"><div>{t("Source message")}: {record.sourceMessageId}</div><div>{t("Destination")}: {record.targetChatId}</div><div>{t("Status")}: {t(({ UNKNOWN: "Unconfirmed sends", FAILED: "Failed", COMPLETED: "Completed", SKIPPED: "Skipped" } as Record<string, string>)[record.status] || record.status)}</div><div>{t("Blocked since")}: {new Date(record.updatedAt).toLocaleString()}</div></div>
      {record.lastErrorCode && <details className="text-xs"><summary className="cursor-pointer">{t("Error details")}</summary><pre className="mt-2 whitespace-pre-wrap break-all rounded bg-muted p-2">{record.lastErrorCode}{"\n"}{record.lastErrorMessage}</pre></details>}
      {editable && <>
        <Button size="sm" variant="outline" disabled={busy} onClick={() => void act("RECONCILE")}>{t(busy ? "Checking..." : "Verify now")}</Button>
        <p className="text-xs text-muted-foreground">{t("Old records without saved delivery evidence need manual verification. A missing temporary message does not prove that delivery failed.")}</p>
        <label className="block space-y-1"><span>{t("Destination message link or ID")}</span><Input value={target} onChange={event => setTarget(event.target.value)} placeholder="https://t.me/…" /></label>
        <label className="block space-y-1"><span>{t("Resolution reason")}</span><Input maxLength={1024} value={reason} onChange={event => setReason(event.target.value)} placeholder={t("Describe what you verified")} /></label>
        <div className="flex flex-wrap gap-2"><Button size="sm" disabled={busy || !reason.trim() || !target.trim()} onClick={() => void act("CONFIRM_SENT")}>{t("Confirm already sent")}</Button><Button size="sm" variant="destructive" disabled={busy || !reason.trim()} onClick={() => void act("SKIP")}>{t("Skip and continue")}</Button></div>
        <label className="flex items-center gap-2 text-xs"><input type="checkbox" checked={checkedMissing} onChange={event => setCheckedMissing(event.target.checked)} />{t("I checked the destination and confirmed this message was not received")}</label>
        <Button size="sm" variant="outline" disabled={busy || !checkedMissing || !reason.trim()} onClick={() => void act("RETRY")}>{t("Retry this message")}</Button>
        <p className="text-xs text-muted-foreground">{t("Skipping releases later messages and leaves an audit record. Use retry in the records list only after checking that the destination did not receive it.")}</p>
      </>}
      {notice && <p role="status" className="rounded border p-2 text-xs">{notice}</p>}
      {data.audit?.length > 0 && <details className="text-xs"><summary>{t("Resolution history")}</summary>{data.audit.map(item => <p key={item.id} className="mt-2">{new Date(item.created_at).toLocaleString()} · {t(({ CONFIRM_SENT: "Confirm already sent", SKIP: "Skip and continue", RETRY: "Retry" } as Record<string, string>)[item.action] || item.action)} · <span translate="no">{item.reason === "Retry requested by operator" ? t(item.reason) : item.reason}</span></p>)}</details>}
    </div>}
  </DialogContent></Dialog>;
}
