"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import useSWR from "swr";
import { POST } from "@/lib/api";
import { useToast } from "@/hooks/use-toast";
import { useWebsocket } from "@/hooks/use-websocket";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog";
import Proxys from "@/components/proxys";

export type ConnectionHealth = {
  telegramId: string; accountName: string; proxy?: string; status: string;
  connectionState: string; probeStatus: string; checkedAt: number;
  lastSuccessAt: number; unhealthySince: number; latencyMs: number | null;
};

const labels: Record<string, string> = {
  HEALTHY: "Connected", CONNECTING: "Reconnecting", ERROR: "Connection unavailable",
  UNKNOWN: "Status unknown", SLEEPING: "Sleeping", INACTIVE: "Login required",
  ready: "Connected", connecting: "Connecting", connectingToProxy: "Connecting to proxy",
  updating: "Synchronizing", waitingForNetwork: "Waiting for network", unknown: "Status unknown",
  OK: "Line reachable", FAILED: "Line test failed",
};

export function healthStatus(item: ConnectionHealth, unavailable = false, now = Date.now()) {
  if (unavailable) return "UNKNOWN";
  if (["SLEEPING", "INACTIVE"].includes(item.status)) return item.status;
  return !item.checkedAt || now - item.checkedAt > 90_000 ? "UNKNOWN" : item.status;
}

function useHealth() {
  return useSWR<ConnectionHealth[]>("/telegram/health", { refreshInterval: 15_000, dedupingInterval: 5_000 });
}

export function ConnectionHealthAlerts() {
  const { data, error } = useHealth();
  const { toast } = useToast();
  const previous = useRef(new Map<string, string>());
  const notices = useRef(new Map<string, () => void>());
  const accounts = Array.isArray(data) ? data : [];
  useEffect(() => {
    if (error || !Array.isArray(data)) return;
    for (const item of data) {
      const status = healthStatus(item);
      const old = previous.current.get(item.telegramId);
      if (status === "ERROR" && old !== "ERROR") {
        const notice = toast({ variant: "error", title: "Connection unavailable", description: item.accountName });
        if (notice?.dismiss) notices.current.set(item.telegramId, notice.dismiss);
      }
      if (old === "ERROR" && ["HEALTHY", "SLEEPING", "INACTIVE"].includes(status)) {
        notices.current.get(item.telegramId)?.(); notices.current.delete(item.telegramId);
        if (status === "HEALTHY") toast({ variant: "success", title: "Connection restored", description: item.accountName });
      }
      // Preserve an outage across reconnecting/unknown snapshots until recovery is confirmed.
      if (old !== "ERROR" || ["ERROR", "HEALTHY", "SLEEPING", "INACTIVE"].includes(status)) previous.current.set(item.telegramId, status);
    }
  }, [data, error, toast]);
  const failures = accounts.filter(item => healthStatus(item, !!error) === "ERROR");
  if (!error && !failures.length) return null;
  return <div role="alert" className="mx-auto mt-3 max-w-7xl rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm">
    <strong>{error ? "Connection status unavailable" : "Telegram connection unavailable"}</strong>
    <span className="mx-2">{failures.map(item => item.accountName).join(", ")}</span>
    <Link className="underline" href="/">Connection health</Link>
  </div>;
}

export function ConnectionHealthPanel() {
  const { data, error, isLoading, mutate } = useHealth();
  const { connectionStatus } = useWebsocket();
  const { toast } = useToast();
  const [checking, setChecking] = useState<string | null>(null);
  const [settings, setSettings] = useState<ConnectionHealth | null>(null);
  const accounts = Array.isArray(data) ? data : [];
  const check = async (item: ConnectionHealth) => {
    setChecking(item.telegramId);
    try { await POST(`/telegram/${item.telegramId}/check-health`); await mutate(); }
    catch { toast({ variant: "error", title: "Connection check failed" }); }
    finally { setChecking(null); }
  };
  return <Card>
    <CardHeader className="px-3 pb-2 pt-3"><CardTitle className="text-sm">Connection health</CardTitle>
      <p className="text-xs text-muted-foreground"><span>Browser connection</span>: <span>{connectionStatus === "Open" ? "Connected" : "Reconnecting"}</span></p>
    </CardHeader>
    <CardContent className="max-h-52 space-y-2 overflow-y-auto px-3 pb-3">
      {isLoading && <p>Checking connection...</p>}
      {error && <p className="text-destructive">Connection status unavailable</p>}
      {!isLoading && !error && !accounts.length && <p>No Accounts Found</p>}
      {accounts.map(item => {
        const status = healthStatus(item, !!error);
        return <div key={item.telegramId} className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-muted/30 p-2">
          <div className="min-w-0 space-y-1 text-xs">
            <div className="flex items-center gap-2"><strong translate="no">{item.accountName}</strong><Badge variant={status === "ERROR" ? "destructive" : status === "HEALTHY" ? "default" : "secondary"}>{labels[status]}</Badge></div>
            <div><span>Connection route</span>: {item.proxy ? <span translate="no">{item.proxy}</span> : <span>Direct connection</span>} · <span>Telegram connection state</span>: <span>{labels[item.connectionState] || labels.UNKNOWN}</span></div>
            <div className="text-muted-foreground"><span>{status === "UNKNOWN" ? labels.UNKNOWN : labels[item.probeStatus] || labels.UNKNOWN}</span>{status !== "UNKNOWN" && item.latencyMs != null && ` · ${item.latencyMs} ms`}</div>
            <details className="text-xs text-muted-foreground"><summary className="cursor-pointer">Connection details</summary><div className="mt-1"><span>Last checked</span>: {item.checkedAt ? new Date(item.checkedAt).toLocaleString() : "—"} · <span>Last successful connection</span>: {item.lastSuccessAt ? new Date(item.lastSuccessAt).toLocaleString() : "—"}</div>
            {status === "ERROR" && item.unhealthySince > 0 && <div className="text-xs text-destructive"><span>Connection issue since</span>: {new Date(item.unhealthySince).toLocaleString()}</div>}</details>
          </div>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" disabled={checking !== null || ["SLEEPING", "INACTIVE"].includes(status)} onClick={() => void check(item)}>{checking === item.telegramId ? "Checking connection..." : "Check now"}</Button>
            <Button variant="outline" size="sm" onClick={() => setSettings(item)}>Proxy settings</Button>
          </div>
        </div>;
      })}
    </CardContent>
    <Dialog open={!!settings} onOpenChange={open => { if (!open) setSettings(null); }}>
      <DialogContent aria-describedby={undefined} className="max-h-[85vh] overflow-y-auto sm:max-w-2xl"><DialogTitle>Proxy settings</DialogTitle>
        {settings && <Proxys telegramId={settings.telegramId} proxyName={settings.proxy || ""} onProxyNameChange={() => { setSettings(null); void mutate(); }} />}
      </DialogContent>
    </Dialog>
  </Card>;
}
