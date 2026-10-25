"use client";

import { useEffect, useState } from "react";
import useSWR from "swr";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { POST } from "@/lib/api";
import { useOptionalLanguage } from "@/i18n/language-provider";

type Settings = {
  enabled: boolean; serverUrl: string; siteUrl: string; keyConfigured: boolean;
  unconfirmedMinutes: number; connectionMinutes: number; stalledMinutes: number; repeatMinutes: number;
  recovery: boolean; events: string[];
  incidents?: { incident_key: string; state: string; last_sent_at: number; last_error?: string; attempts: number }[];
};
const eventLabels: Record<string, string> = { UNCONFIRMED: "Unconfirmed sends", FAILED: "Failed earlier messages", CONNECTION: "Connection unavailable", LOGIN: "Login required", HISTORY: "History task failures", STALLED: "Queue without progress" };

export function BarkNotificationSettings() {
  const t = useOptionalLanguage()?.t ?? ((text: string) => text);
  const { data, error, mutate } = useSWR<Settings>("/notifications/bark");
  const [draft, setDraft] = useState<Settings | null>(null);
  const [deviceKey, setDeviceKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  useEffect(() => { if (data && !draft) setDraft(data); }, [data, draft]);
  async function save(test = false) {
    if (!draft) return;
    setBusy(true); setNotice("");
    try {
      const saved = await POST("/notifications/bark", { ...draft, deviceKey }) as Settings;
      setDraft(saved); setDeviceKey(""); await mutate(saved, false);
      if (test) { await POST("/notifications/bark/test"); setNotice(t("Test notification submitted; check your phone")); }
      else setNotice(t("Notification settings saved"));
    } catch (failure) { setNotice(t(failure instanceof Error ? failure.message : "Operation failed")); }
    finally { setBusy(false); }
  }
  if (error) return <p className="p-3 text-sm text-destructive">{t("Failed to load notification settings")}</p>;
  if (!draft) return <p className="p-3 text-sm">{t("Loading...")}</p>;
  const update = <K extends keyof Settings>(key: K, value: Settings[K]) => setDraft({ ...draft, [key]: value });
  return <div className="space-y-4 overflow-y-auto p-1 text-sm">
    <div><h3 className="font-semibold">{t("Bark notifications")}</h3><p className="mt-1 text-xs text-muted-foreground">{t("The server sends actionable alerts even when the browser is closed.")}</p></div>
    <label className="flex items-center gap-2"><input type="checkbox" checked={draft.enabled} onChange={event => update("enabled", event.target.checked)} />{t("Enable Bark notifications")}</label>
    <div className="grid gap-3 sm:grid-cols-2">
      <label className="space-y-1"><span>{t("Bark server address")}</span><Input value={draft.serverUrl} onChange={event => update("serverUrl", event.target.value)} placeholder="https://api.day.app" /></label>
      <label className="space-y-1"><span>{t("Device key")}</span><Input type="password" autoComplete="new-password" value={deviceKey} onChange={event => setDeviceKey(event.target.value)} placeholder={t(draft.keyConfigured ? "Saved; leave blank to keep" : "Enter your Bark device key")} /></label>
      <label className="space-y-1 sm:col-span-2"><span>{t("Management page address (optional)")}</span><Input value={draft.siteUrl} onChange={event => update("siteUrl", event.target.value)} placeholder="https://…" /><span className="block text-xs text-muted-foreground">{t("Use an address reachable from your phone. Tapping an alert opens the related page.")}</span></label>
    </div>
    <fieldset className="rounded-lg border p-3"><legend className="px-1">{t("Notify about")}</legend><div className="grid gap-2 sm:grid-cols-2">{Object.entries(eventLabels).map(([key, label]) => <label key={key} className="flex items-center gap-2"><input type="checkbox" checked={draft.events.includes(key)} onChange={event => update("events", event.target.checked ? [...draft.events, key] : draft.events.filter(item => item !== key))} />{t(label)}</label>)}</div></fieldset>
    <div className="grid grid-cols-2 gap-3">
      {([ ["unconfirmedMinutes", "Unconfirmed delay (minutes)"], ["connectionMinutes", "Connection delay (minutes)"], ["stalledMinutes", "No progress delay (minutes)"], ["repeatMinutes", "Repeat reminder (minutes, 0 = off)"] ] as const).map(([key, label]) => <label key={key} className="space-y-1 text-xs"><span>{t(label)}</span><Input type="number" min={key === "repeatMinutes" ? 0 : 1} max={1440} value={draft[key]} onChange={event => update(key, Number(event.target.value))} /></label>)}
    </div>
    <label className="flex items-center gap-2"><input type="checkbox" checked={draft.recovery} onChange={event => update("recovery", event.target.checked)} />{t("Notify once when an issue clears")}</label>
    <p className="text-xs text-muted-foreground">{t("Repeated checks and restarts are deduplicated. Normal cooldowns, quotas and intentional pauses do not trigger alerts. Notification content excludes message text and phone numbers.")}</p>
    <p className="text-xs text-muted-foreground">{t("Application shutdown or complete network failure requires independent NAS monitoring.")}</p>
    <div className="flex flex-wrap gap-2"><Button size="sm" disabled={busy} onClick={() => void save()}>{t("Save Settings")}</Button><Button size="sm" variant="outline" disabled={busy || (!deviceKey && !draft.keyConfigured)} onClick={() => void save(true)}>{t("Save and send test")}</Button></div>
    {notice && <p role="status" className="rounded border p-2 text-xs">{notice}</p>}
    {!!data?.incidents?.length && <details className="text-xs"><summary>{t("Recent notification results")}</summary><div className="mt-2 max-h-40 space-y-2 overflow-auto">{data.incidents.map(item => <div key={item.incident_key} className="rounded border p-2"><span>{t(({ OPEN: "Issue active", RECOVERY: "Recovery notification pending", CLOSED: "Issue closed" } as Record<string, string>)[item.state] || item.state)}</span> · {item.last_sent_at ? new Date(item.last_sent_at).toLocaleString() : t("Not sent yet")}{item.last_error && <p className="text-destructive">{t("Bark push failed; check notification settings")}</p>}</div>)}</div></details>}
  </div>;
}
