"use client";

import { useState } from "react";
import { MoreHorizontal, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { healthStatus, labels, useHealth } from "@/components/connection-health";
import Proxys from "@/components/proxys";
import { POST } from "@/lib/api";
import { useOptionalLanguage } from "@/i18n/language-provider";

export function AccountConnection({ telegramId, configuredProxy }: { telegramId: string; configuredProxy?: string }) {
  const t = useOptionalLanguage()?.t ?? ((text: string) => text);
  const { data, error, mutate } = useHealth();
  const item = Array.isArray(data) ? data.find(account => account.telegramId === telegramId) : undefined;
  const status = item ? healthStatus(item, !!error) : "UNKNOWN";
  const [checking, setChecking] = useState(false);
  const [settings, setSettings] = useState(false);
  const [notice, setNotice] = useState("");
  async function check() {
    setChecking(true); setNotice("");
    try { await POST(`/telegram/${telegramId}/check-health`); await mutate(); }
    catch { setNotice(t("Connection check failed")); }
    finally { setChecking(false); }
  }
  return <div className="space-y-2 border-t pt-2 text-xs" onClick={event => event.stopPropagation()}>
    <div className="flex items-center justify-between gap-2">
      <Badge variant={status === "ERROR" ? "destructive" : "outline"} className="gap-1.5 px-1.5 text-[11px]">
        <span aria-hidden className={`size-1.5 rounded-full ${status === "HEALTHY" ? "bg-emerald-500" : status === "ERROR" ? "bg-red-500" : "bg-amber-500"}`} />
        {t(labels[status] || "Status unknown")}
      </Badge>
      <span className="text-muted-foreground tabular-nums">{item && status !== "UNKNOWN" && item.latencyMs != null ? `${item.latencyMs} ${t("Milliseconds")}` : "—"}</span>
    </div>
    <div className="flex min-w-0 items-center gap-1 text-muted-foreground"><span className="shrink-0">{t("Current proxy")}:</span><span translate="no" className="truncate" title={item?.proxy || undefined}>{item ? item.proxy || t("Direct connection") : t("Status unknown")}</span></div>
    <div className="flex items-center justify-between gap-2">
      <span className="text-muted-foreground">{t(item ? labels[item.probeStatus] || "Status unknown" : "Status unknown")}</span>
      <div className="flex gap-1">
        <Button size="sm" variant="ghost" className="h-7 gap-1 px-2 text-xs" aria-label={t("Check now")} disabled={checking || ["SLEEPING", "INACTIVE"].includes(status)} onClick={() => void check()}><RefreshCw className={`size-3 ${checking ? "animate-spin" : ""}`} />{t("Check")}</Button>
        <DropdownMenu><DropdownMenuTrigger asChild><Button size="icon" variant="ghost" className="size-7" aria-label={t("Account options")}><MoreHorizontal className="size-4" /></Button></DropdownMenuTrigger><DropdownMenuContent align="end"><DropdownMenuItem onSelect={() => setSettings(true)}>{t("Change proxy")}</DropdownMenuItem></DropdownMenuContent></DropdownMenu>
      </div>
    </div>
    {notice && <p role="status" className="text-destructive">{notice}</p>}
    <Dialog open={settings} onOpenChange={setSettings}><DialogContent aria-describedby={undefined} className="max-h-[85dvh] overflow-y-auto sm:max-w-2xl"><DialogTitle>{t("Proxy settings")}</DialogTitle>
      <Proxys enableSelect telegramId={telegramId} proxyName={item?.proxy ?? configuredProxy ?? ""} onProxyNameChange={() => { setSettings(false); void mutate(); void check(); }} />
    </DialogContent></Dialog>
  </div>;
}
