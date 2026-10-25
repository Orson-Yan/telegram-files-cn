"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import useSWR from "swr";
import {
  AlertTriangle,
  CheckCircle2,
  CloudUpload,
  Copy,
  Filter,
  History,
  Loader2,
  Pause,
  Play,
  Plus,
  RefreshCw,
  Send,
  Settings2,
  ShieldCheck,
  SkipForward,
  Trash2,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

function getStatusBadge(status: string) {
  switch (status) {
    case "COMPLETED":
      return (
        <Badge
          variant="outline"
          className="border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 font-medium"
        >
          COMPLETED
        </Badge>
      );
    case "FAILED":
    case "UNKNOWN":
      return (
        <Badge
          variant="outline"
          className="border-rose-500/30 bg-rose-500/10 text-rose-600 dark:text-rose-400 font-medium"
        >
          {status}
        </Badge>
      );
    case "SKIPPED":
      return (
        <Badge
          variant="outline"
          className="border-muted bg-muted/60 text-muted-foreground font-medium"
        >
          SKIPPED
        </Badge>
      );
    case "STAGED":
      return (
        <Badge
          variant="outline"
          className="border-amber-500/30 bg-amber-500/10 text-amber-600 dark:text-amber-400 font-medium"
        >
          Waiting for ordered release
        </Badge>
      );
    case "SENDING":
    case "PENDING":
    case "RETRY":
      return (
        <Badge
          variant="outline"
          className="border-sky-500/30 bg-sky-500/10 text-sky-600 dark:text-sky-400 font-medium"
        >
          {status}
        </Badge>
      );
    default:
      return <Badge variant="outline">{status}</Badge>;
  }
}
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import { RuleChatPicker } from "@/components/rule-chat-picker";
import { ArchiveDailyStatistics } from "@/components/archive-daily-statistics";
import { ArchiveRecoveryDialog } from "@/components/archive-recovery-dialog";
import { RuleTopicPicker } from "@/components/rule-topic-picker";
import { useTelegramAccount } from "@/hooks/use-telegram-account";
import { useToast } from "@/hooks/use-toast";
import { normalizeAuto, validateArchiveDestination } from "@/lib/automation";
import { POST, request } from "@/lib/api";
import type {
  ArchiveMode,
  ArchiveScope,
  AutoArchiveRule,
  CloudArchiveOverview,
  CloudArchiveHistoryJob,
  CloudArchiveRecord,
  CloudArchiveRuleOverview,
  TelegramChat,
} from "@/lib/types";

type RuleDraft = {
  telegramId: string;
  sourceChatId: string;
  sourceIsForum: boolean;
  targetIsForum: boolean;
  enabled: boolean;
  rule: AutoArchiveRule;
};

const WAIT_REASONS: Record<string, string> = {
  LIVE: "Waiting for new messages", PAUSED: "Rule paused", CONNECTION_ERROR: "Connection unavailable",
  CONNECTION_CONNECTING: "Reconnecting", CONNECTION_SLEEPING: "Account sleeping", CONNECTION_INACTIVE: "Login required",
  FLOOD_WAIT: "Waiting for Telegram cooldown", DAILY_QUOTA: "Daily quota reached", UNCONFIRMED: "Waiting for send confirmation",
  PREDECESSOR_FAILED: "Waiting for a failed earlier message", SENDING: "Sending", RETRY_WAIT: "Waiting for retry",
  HISTORY_PAUSED: "History task paused", ORDERED_RELEASE: "Waiting for ordered release", RECOVERING: "Recovering missed messages",
  QUEUED: "Messages queued", SYNC_ERROR: "Synchronization error", ACCOUNT_QUOTA: "Waiting for account safety quota",
};

const FILE_TYPES = ["photo", "video", "audio", "file"] as const;

function emptyDraft(telegramId = ""): RuleDraft {
  return {
    telegramId,
    sourceChatId: "",
    sourceIsForum: false,
    targetIsForum: false,
    enabled: true,
    rule: {
      sourceTopicId: 0,
      targetChatId: 0,
      targetTopicId: 0,
      topicMode: "MERGE",
      mode: "COPY",
      scope: "ALL_MESSAGES",
      fileTypes: [],
      query: "",
      filterExpr: "",
      preserveCaption: true,
      disableNotification: true,
      strictOrder: false,
      recoveryEnabled: true,
      initialSyncMode: "NOW",
      minSize: 0,
      maxSize: 0,
      extensions: [],
      cleanCaption: false,
      stripLinks: false,
      stripUsernames: false,
      captionReplacements: [],
      captionSuffix: "",
    },
  };
}

function toDraft(rule: CloudArchiveRuleOverview): RuleDraft {
  return {
    telegramId: rule.telegramId,
    sourceChatId: rule.sourceChatId,
    sourceIsForum: Boolean(rule.sourceIsForum),
    targetIsForum: Boolean(rule.targetIsForum),
    enabled: rule.enabled,
    rule: {
      ...rule.rule,
      sourceTopicId: String(rule.rule.sourceTopicId || ""),
      targetChatId: String(rule.targetChatId),
      targetTopicId: String(rule.rule.targetTopicId || ""),
      topicMode: rule.rule.topicMode || "MERGE",
      strictOrder: Boolean(rule.rule.strictOrder),
      recoveryEnabled: rule.rule.recoveryEnabled !== false,
      initialSyncMode: rule.rule.initialSyncMode || "NOW",
      minSize: rule.rule.minSize || 0,
      maxSize: rule.rule.maxSize || 0,
      extensions: rule.rule.extensions || [],
      cleanCaption: Boolean(rule.rule.cleanCaption),
      stripLinks: Boolean(rule.rule.stripLinks),
      stripUsernames: Boolean(rule.rule.stripUsernames),
      captionReplacements: rule.rule.captionReplacements || [],
      captionSuffix: rule.rule.captionSuffix || "",
    },
  };
}

async function currentAuto(telegramId: string, chatId: string) {
  const chats = await request<TelegramChat[]>(
    `/telegram/${telegramId}/chats?query=&archived=false&chatId=${chatId}`,
  );
  const chat = chats.find((chat) => chat.id === chatId);
  if (!chat) throw new Error("Unable to load the source chat settings. Refresh and try again");
  return normalizeAuto(chat.auto);
}

async function saveDraft(draft: RuleDraft, previousTargetChatId?: string) {
  const auto = await currentAuto(draft.telegramId, draft.sourceChatId);
  const config = {
    enabled: draft.enabled,
    rule: {
      ...draft.rule,
      sourceTopicId: Number(draft.rule.sourceTopicId || 0),
      targetChatId: Number(draft.rule.targetChatId),
      targetTopicId: Number(draft.rule.targetTopicId || 0),
      minSize: Number(draft.rule.minSize || 0),
      maxSize: Number(draft.rule.maxSize || 0),
      extensions: (draft.rule.extensions || []).map((ext) => ext.trim().toLowerCase()).filter(Boolean),
      cleanCaption: Boolean(draft.rule.cleanCaption),
      stripLinks: Boolean(draft.rule.stripLinks),
      stripUsernames: Boolean(draft.rule.stripUsernames),
      captionReplacements: (draft.rule.captionReplacements || []).filter(
        (cr) => cr.pattern && cr.pattern.trim().length > 0,
      ),
      captionSuffix: draft.rule.captionSuffix || "",
    },
  };
  validateArchiveDestination(auto, config, previousTargetChatId);
  await POST(
    `/${draft.telegramId}/file/update-auto-settings?telegramId=${draft.telegramId}&chatId=${draft.sourceChatId}`,
    { archiveUpdate: config, previousTargetChatId },
  );
}

function StatCard({
  label,
  value,
  icon,
}: {
  label: string;
  value: number;
  icon: React.ReactNode;
}) {
  return (
    <Card>
      <CardContent className="flex items-center justify-between gap-2 p-2.5">
        <div>
          <p className="text-xs text-muted-foreground">{label}</p>
          <p className="text-base font-semibold tabular-nums">{value.toLocaleString()}</p>
        </div>
        <div className="rounded-full bg-muted p-1.5 text-muted-foreground [&_svg]:size-4">
          {icon}
        </div>
      </CardContent>
    </Card>
  );
}

export function CloudArchiveManager() {
  const { getAccounts } = useTelegramAccount();
  const accounts = getAccounts("active");
  const { toast } = useToast();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [resolving, setResolving] = useState<string | null>(null);
  useEffect(() => { setResolving(new URLSearchParams(window.location.search).get("record")); }, []);
  const [editing, setEditing] = useState<CloudArchiveRuleOverview | null>(null);
  const [draft, setDraft] = useState<RuleDraft>(() => emptyDraft());
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState<CloudArchiveRuleOverview | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [deleteSelection, setDeleteSelection] = useState<CloudArchiveRuleOverview[]>([]);
  const [retrying, setRetrying] = useState<CloudArchiveRecord | null>(null);
  const [ruleSearch, setRuleSearch] = useState("");
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [bulkBusy, setBulkBusy] = useState(false);
  const [recordRoute, setRecordRoute] = useState("ALL");
  const ruleKey = (rule: CloudArchiveRuleOverview) => `${rule.telegramId}:${rule.sourceChatId}:${rule.targetChatId}`;
  const [checking, setChecking] = useState<"validate" | "test" | null>(null);
  const [historyRule, setHistoryRule] =
    useState<CloudArchiveRuleOverview | null>(null);
  const [historyLimit, setHistoryLimit] = useState("ALL");
  const [historyDailyLimitType, setHistoryDailyLimitType] = useState<
    "200" | "500" | "1000" | "0" | "custom"
  >("500");
  const [historyCustomDailyLimit, setHistoryCustomDailyLimit] = useState("300");
  const [historySaving, setHistorySaving] = useState(false);

  const [adjustLimitJob, setAdjustLimitJob] =
    useState<CloudArchiveHistoryJob | null>(null);
  const [adjustLimitType, setAdjustLimitType] = useState<
    "200" | "500" | "1000" | "0" | "custom"
  >("500");
  const [adjustCustomLimit, setAdjustCustomLimit] = useState("300");
  const [adjustSaving, setAdjustSaving] = useState(false);

  const {
    data: overview,
    error,
    isLoading,
    mutate: reloadOverview,
  } = useSWR<CloudArchiveOverview>("/cloud-archive/overview", {
    refreshInterval: 5000,
  });
  const [recordStatusFilter, setRecordStatusFilter] = useState<string>("ALL");
  const recordsUrl = useMemo(() => {
    const params = new URLSearchParams({ limit: "100" });
    if (recordRoute !== "ALL") {
      const [account, source, target] = recordRoute.split(":");
      params.set("telegramId", account!); params.set("sourceChatId", source!); params.set("targetChatId", target!);
    }
    if (recordStatusFilter && recordStatusFilter !== "ALL") {
      params.set("status", recordStatusFilter);
    }
    return `/cloud-archive/records?${params.toString()}`;
  }, [recordStatusFilter, recordRoute]);
  const { data: records, mutate: reloadRecords } = useSWR<CloudArchiveRecord[]>(
    recordsUrl,
    { refreshInterval: 5000 },
  );
  const { data: historyJobs, mutate: reloadHistory } = useSWR<
    CloudArchiveHistoryJob[]
  >("/cloud-archive/history?limit=100", { refreshInterval: 5000 });

  useEffect(() => {
    if (!draft.telegramId && accounts.length > 0) {
      setDraft(emptyDraft(accounts[0]?.id));
    }
  }, [accounts, draft.telegramId]);

  const validDraft = useMemo(() => {
    const targetChatId = Number(draft.rule.targetChatId);
    const sourceTopicId = Number(draft.rule.sourceTopicId || 0);
    const targetTopicId = Number(draft.rule.targetTopicId || 0);
    const differentEndpoint =
      draft.sourceChatId !== String(targetChatId) ||
      (sourceTopicId !== 0 &&
        targetTopicId !== 0 &&
        sourceTopicId !== targetTopicId);
    return Boolean(
      draft.telegramId &&
        draft.sourceChatId &&
        targetChatId !== 0 &&
        differentEndpoint,
    );
  }, [draft]);

  const openNew = () => {
    setEditing(null);
    setDraft(emptyDraft(accounts[0]?.id ?? ""));
    setDialogOpen(true);
  };

  const openEdit = (rule: CloudArchiveRuleOverview) => {
    setEditing(rule);
    setDraft(toDraft(rule));
    setDialogOpen(true);
  };

  const validate = async (sendTest: boolean) => {
    if (!validDraft) return;
    setChecking(sendTest ? "test" : "validate");
    try {
      const response = await POST(
        sendTest ? "/cloud-archive/test" : "/cloud-archive/validate",
        {
          telegramId: draft.telegramId,
          sourceChatId: draft.sourceChatId,
          rule: {
            ...draft.rule,
            sourceTopicId: Number(draft.rule.sourceTopicId || 0),
            targetChatId: Number(draft.rule.targetChatId),
            targetTopicId: Number(draft.rule.targetTopicId || 0),
          },
        },
      );
      if (sendTest) {
        toast({ variant: "success", title: "Test message sent" });
      } else {
        const details = [
          response.message,
          ...(Array.isArray(response.warnings) ? response.warnings : []),
        ]
          .filter(Boolean)
          .join(" · ");
        toast({
          variant: response.valid ? "success" : "warning",
          title: response.valid
            ? "Permission check passed"
            : "Permission check failed",
          description: details,
        });
      }
    } catch (failure) {
      toast({
        variant: "error",
        title: sendTest ? "Test message failed" : "Permission check failed",
        description:
          failure instanceof Error ? failure.message : String(failure),
      });
    } finally {
      setChecking(null);
    }
  };

  const save = async () => {
    if (!validDraft) return;
    setSaving(true);
    try {
      await saveDraft(draft, editing?.targetChatId);
      let historyQueued = false;
      let historyError: string | undefined;
      if (
        !editing &&
        draft.enabled &&
        draft.rule.initialSyncMode === "FULL"
      ) {
        try {
          await POST("/cloud-archive/history", {
            telegramId: draft.telegramId,
            sourceChatId: draft.sourceChatId,
            targetChatId: draft.rule.targetChatId,
            scanMode: "ALL",
            maxMessages: 0,
          });
          historyQueued = true;
        } catch (failure) {
          historyError =
            failure instanceof Error ? failure.message : String(failure);
        }
      }
      await Promise.all([reloadOverview(), reloadRecords(), reloadHistory()]);
      setDialogOpen(false);
      toast({
        variant: historyError ? "warning" : "success",
        title: historyQueued
          ? "Rule saved and full history queued"
          : "Cloud archive rule saved",
        description: historyError
          ? `The rule is active, but its history task could not be created: ${historyError}`
          : undefined,
      });
    } catch (failure) {
      toast({
        variant: "error",
        title: "Failed to save cloud archive rule",
        description:
          failure instanceof Error ? failure.message : String(failure),
      });
    } finally {
      setSaving(false);
    }
  };

  const deleteRule = async () => {
    if (!deleting) return;
    const targets = deleteSelection.length ? deleteSelection : [deleting];
    setDeleteBusy(true);
    try {
      const outcomes = await Promise.allSettled(targets.map(rule => POST(
        `/${rule.telegramId}/file/update-auto-settings?chatId=${rule.sourceChatId}`,
        { archiveDeleteTargetChatId: rule.targetChatId })));
      const failures = targets.filter((_, index) => outcomes[index]?.status === "rejected");
      setSelectedKeys(failures.map(ruleKey));
      if (failures.length) {
        setDeleteSelection(failures); setDeleting(failures[0]!);
        throw new Error(`${outcomes.length - failures.length} / ${outcomes.length}`);
      }
      setDeleting(null); setDeleteSelection([]);
      toast({ variant: "success", title: "Archive rule deleted" });
    } catch (failure) {
      toast({ variant: "error", title: "Failed to delete archive rule", description: failure instanceof Error ? failure.message : String(failure) });
    } finally { setDeleteBusy(false); await Promise.allSettled([reloadOverview(), reloadRecords(), reloadHistory()]); }
  };

  const visibleRules = (overview?.rules || []).filter(rule =>
    [rule.accountName, rule.sourceChatName, rule.targetChatName, rule.sourceChatId, rule.targetChatId].join(" ").toLowerCase().includes(ruleSearch.trim().toLowerCase()));
  const selectedRules = (overview?.rules || []).filter(rule => selectedKeys.includes(ruleKey(rule)));
  const batchToggle = async (enabled: boolean) => {
    setBulkBusy(true);
    try {
      const outcomes = await Promise.allSettled(selectedRules.map(rule => saveDraft({ ...toDraft(rule), enabled }, rule.targetChatId)));
      const failed = selectedRules.filter((_, index) => outcomes[index]?.status === "rejected");
      setSelectedKeys(failed.map(ruleKey));
      toast({ variant: failed.length ? "error" : "success", title: failed.length ? "Some rules could not be updated" : "Rules updated", description: `${outcomes.length - failed.length} / ${outcomes.length}` });
      await reloadOverview().catch(() => undefined);
    } finally { setBulkBusy(false); }
  };
  const deleteTargets = deleteSelection.length ? deleteSelection : deleting ? [deleting] : [];
  const recordScope = () => {
    if (recordRoute === "ALL") return { telegramId: 0 };
    const [telegramId, chatId, targetChatId] = recordRoute.split(":");
    return { telegramId, chatId, targetChatId };
  };

  const toggleRule = async (rule: CloudArchiveRuleOverview) => {
    try {
      await saveDraft({ ...toDraft(rule), enabled: !rule.enabled }, rule.targetChatId);
      await reloadOverview();
    } catch (failure) {
      toast({
        variant: "error",
        title: "Failed to update cloud archive rule",
        description:
          failure instanceof Error ? failure.message : String(failure),
      });
    }
  };

  const retry = async (record: CloudArchiveRecord, confirmed = false) => {
    if (record.status === "UNKNOWN" && !confirmed) { setRetrying(record); return; }
    try {
      await POST(`/cloud-archive/records/${record.id}/retry`, { confirmUncertain: confirmed });
      await Promise.all([reloadOverview(), reloadRecords()]);
      setRetrying(null);
      toast({ variant: "success", title: "Archive record queued for retry" });
    } catch (failure) {
      toast({
        variant: "error",
        title: "Retry failed",
        description:
          failure instanceof Error ? failure.message : String(failure),
      });
    }
  };

  const retryAllFailed = async () => {
    try {
      const res = (await POST("/cloud-archive/records/retry-all", {
        ...recordScope(),
      })) as { count?: number };
      await Promise.all([reloadOverview(), reloadRecords()]);
      toast({
        variant: "success",
        title: "Retried failed records",
        description: `Successfully queued ${res?.count ?? 0} record(s) for retry.`,
      });
    } catch (failure) {
      toast({
        variant: "error",
        title: "Retry all failed",
        description:
          failure instanceof Error ? failure.message : String(failure),
      });
    }
  };

  const clearRecords = async () => {
    try {
      const res = (await POST("/cloud-archive/records/clear", {
        ...recordScope(),
        status: recordStatusFilter !== "ALL" ? recordStatusFilter : "ALL",
      })) as { count?: number };
      await Promise.all([reloadOverview(), reloadRecords()]);
      toast({
        variant: "success",
        title: "Records cleared",
        description: `Removed ${res?.count ?? 0} record(s).`,
      });
    } catch (failure) {
      toast({
        variant: "error",
        title: "Clear failed",
        description:
          failure instanceof Error ? failure.message : String(failure),
      });
    }
  };

  const createHistory = async () => {
    if (!historyRule) return;
    setHistorySaving(true);
    const dailyLimit =
      historyDailyLimitType === "custom"
        ? Math.max(0, parseInt(historyCustomDailyLimit, 10) || 0)
        : parseInt(historyDailyLimitType, 10);
    try {
      await POST("/cloud-archive/history", {
        telegramId: historyRule.telegramId,
        sourceChatId: historyRule.sourceChatId,
        targetChatId: historyRule.targetChatId,
        scanMode: historyLimit === "ALL" ? "ALL" : "LIMIT",
        maxMessages: historyLimit === "ALL" ? 0 : Number(historyLimit),
        dailyLimit,
      });
      await reloadHistory();
      setHistoryRule(null);
      toast({ variant: "success", title: "Historical archive task queued" });
    } catch (failure) {
      toast({
        variant: "error",
        title: "Failed to start history task",
        description:
          failure instanceof Error ? failure.message : String(failure),
      });
    } finally {
      setHistorySaving(false);
    }
  };

  const saveDailyLimit = async () => {
    if (!adjustLimitJob) return;
    setAdjustSaving(true);
    const targetLimit =
      adjustLimitType === "custom"
        ? Math.max(0, parseInt(adjustCustomLimit, 10) || 0)
        : parseInt(adjustLimitType, 10);
    try {
      await POST(`/cloud-archive/history/${adjustLimitJob.id}/daily-limit`, {
        dailyLimit: targetLimit,
      });
      await reloadHistory();
      setAdjustLimitJob(null);
      toast({ variant: "success", title: "Daily limit updated" });
    } catch (failure) {
      toast({
        variant: "error",
        title: "Failed to update daily limit",
        description:
          failure instanceof Error ? failure.message : String(failure),
      });
    } finally {
      setAdjustSaving(false);
    }
  };

  const historyAction = async (
    job: CloudArchiveHistoryJob,
    action: "pause" | "resume" | "cancel" | "delete",
  ) => {
    try {
      await POST(`/cloud-archive/history/${job.id}/${action}`);
      await reloadHistory();
    } catch (failure) {
      toast({
        variant: "error",
        title: `Failed to ${action} history task`,
        description:
          failure instanceof Error ? failure.message : String(failure),
      });
    }
  };

  if (isLoading) {
    return (
      <div className="flex justify-center p-12 text-muted-foreground">
        <Loader2 className="animate-spin" />
      </div>
    );
  }

  if (error || !overview) {
    return (
      <Card>
        <CardContent className="flex items-center gap-2 p-6 text-destructive">
          <AlertTriangle /> Failed to load cloud archive rules
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="space-y-6">
      {overview.accountCooldowns &&
        Object.entries(overview.accountCooldowns).some(
          ([_, c]) => c && c.remainingSeconds > 0,
        ) && (
          <div className="flex items-start gap-3 rounded-lg border border-amber-500/30 bg-amber-500/10 p-4 text-amber-800 dark:text-amber-200">
            <AlertTriangle className="h-5 w-5 shrink-0 text-amber-600 dark:text-amber-400 mt-0.5" />
            <div className="space-y-1 text-sm">
              <p className="font-semibold">
                Flood Control Active
              </p>
              <div className="text-xs opacity-90 space-y-0.5">
                {Object.entries(overview.accountCooldowns)
                  .filter(([_, c]) => c && c.remainingSeconds > 0)
                  .map(([accId, c]) => {
                    const acc = accounts.find((a) => String(a.id) === accId);
                    const name = acc ? acc.name : `Account #${accId}`;
                    const minutes = Math.floor(c.remainingSeconds / 60);
                    const seconds = c.remainingSeconds % 60;
                    const timeText =
                      minutes > 0
                        ? `${minutes}m ${seconds}s`
                        : `${seconds}s`;
                    return (
                      <p key={accId}>
                        • {name}: Telegram platform temporary flood wait triggered. Resuming automatically in{" "}
                        <span className="font-mono font-bold text-amber-700 dark:text-amber-300">
                          {timeText}
                        </span>.
                      </p>
                    );
                  })}
              </div>
            </div>
          </div>
        )}

      <ArchiveDailyStatistics rules={overview.rules} />
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5">
        <StatCard
          label="Completed"
          value={overview.statistics.completed}
          icon={<CheckCircle2 className="text-green-500" />}
        />
        <StatCard
          label="Pending"
          value={overview.statistics.pending}
          icon={<CloudUpload className="text-blue-500" />}
        />
        <StatCard
          label="Skipped"
          value={overview.statistics.skipped}
          icon={<SkipForward className="text-amber-500" />}
        />
        <StatCard
          label="Failed"
          value={overview.statistics.failed}
          icon={<AlertTriangle className="text-red-500" />}
        />
        <StatCard label="Unconfirmed sends" value={overview.statistics.unconfirmed || 0} icon={<AlertTriangle className="text-amber-500" />} />
      </div>

      <Tabs defaultValue="rules">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <TabsList>
            <TabsTrigger value="rules">Archive rules</TabsTrigger>
            <TabsTrigger value="history">History tasks</TabsTrigger>
            <TabsTrigger value="records">Archive records</TabsTrigger>
          </TabsList>
          <Button onClick={openNew} disabled={accounts.length === 0}>
            <Plus data-icon="inline-start" /> New rule
          </Button>
        </div>

        <TabsContent value="rules" className="mt-4 space-y-3">
          <Input aria-label="Search archive rules" placeholder="Search account, source or destination" value={ruleSearch} onChange={event => setRuleSearch(event.target.value)} />
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="outline" size="sm" onClick={() => setSelectedKeys(visibleRules.map(ruleKey))}>Select visible rules</Button>
            <Button variant="outline" size="sm" onClick={() => setSelectedKeys([])}>Clear selection</Button>
            <span><span>Selected</span>: {selectedRules.length}</span>
            <Button variant="outline" size="sm" disabled={!selectedRules.length || bulkBusy || deleteBusy} onClick={() => void batchToggle(false)}>Pause selected</Button>
            <Button variant="outline" size="sm" disabled={!selectedRules.length || bulkBusy || deleteBusy} onClick={() => void batchToggle(true)}>Resume selected</Button>
            <Button variant="destructive" size="sm" disabled={!selectedRules.length || bulkBusy || deleteBusy} onClick={() => { setDeleteSelection(selectedRules); setDeleting(selectedRules[0]!); }}>Delete selected</Button>
          </div>
          {overview.rules.length === 0 ? (
            <Card>
              <CardContent className="p-8 text-center text-muted-foreground">
                No cloud archive rules. Create one to copy new Telegram messages
                without downloading them first.
              </CardContent>
            </Card>
          ) : (
            visibleRules.map((item) => (
              <Card key={`${item.telegramId}:${item.sourceChatId}:${item.targetChatId}`}>
                <CardHeader className="pb-3">
                  <label className="flex items-center gap-2 text-xs"><input type="checkbox" aria-label="Select rule" checked={selectedKeys.includes(ruleKey(item))} disabled={bulkBusy || deleteBusy} onChange={event => setSelectedKeys(previous => event.target.checked ? [...previous, ruleKey(item)] : previous.filter(key => key !== ruleKey(item)))} /><span>Select rule</span></label>
                  <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                    <div className="min-w-0">
                      <CardTitle className="flex flex-wrap items-center gap-2 text-base">
                        <span translate="no">{item.sourceChatName}</span>
                        {Number(item.rule.sourceTopicId || 0) !== 0 && (
                          <span className="text-xs text-muted-foreground">
                            topic #{item.rule.sourceTopicId}
                          </span>
                        )}
                        <span className="text-muted-foreground">→</span>
                        <span translate="no">{item.targetChatName}</span>
                        {Number(item.rule.targetTopicId || 0) !== 0 && (
                          <span className="text-xs text-muted-foreground">
                            topic #{item.rule.targetTopicId}
                          </span>
                        )}
                      </CardTitle>
                      <p className="mt-1 text-sm text-muted-foreground">
                        {item.accountName} ·{" "}
                        {item.rule.mode === "COPY"
                          ? "Copy archive"
                          : "Forward with source"}{" "}
                        ·{" "}
                        {item.rule.scope === "ALL_MESSAGES"
                          ? "All messages"
                          : "Media only"}
                        {item.rule.topicMode === "PRESERVE"
                          ? " · Preserve topics"
                          : ""}
                      </p>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <Badge variant={item.enabled ? "default" : "secondary"}>
                        {item.enabled ? "Running" : "Paused"}
                      </Badge>
                      <Badge
                        variant={
                          item.syncStatus === "ERROR" ? "destructive" : "outline"
                        }
                      >
                        Sync: {item.syncStatus || "INITIALIZING"}
                      </Badge>
                    </div>
                  </div>
                </CardHeader>
                <CardContent className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                  <div className="space-y-1 text-sm text-muted-foreground">
                    <div>
                      Target auto download:{" "}
                      {item.targetDownloadEnabled ? "Enabled" : "Disabled"}
                    </div>
                    <div>
                      <span>Recovery</span>: <span>{item.rule.recoveryEnabled === false ? "Off" : "On"}</span>
                      {" · "}<span>{item.rule.strictOrder ? "Strict order" : "Live priority"}</span>
                      {item.syncTopicCount
                        ? <span>{" · "}{item.syncTopicCount} <span>checkpoints</span></span>
                        : ""}
                      {item.syncLastObservedMessageId
                        ? <span>{" · "}<span>Checked through</span> #{item.syncLastObservedMessageId}</span>
                        : ""}
                    </div>
                    {item.syncStatus === "RECOVERING" && (
                      <div className="tabular-nums">
                        Gap scan {item.syncScannedCount || 0} · matched{" "}
                        {item.syncMatchedCount || 0} · queued{" "}
                        {item.syncQueuedCount || 0}
                      </div>
                    )}
                    <div className="text-sm"><span>Current activity</span>: <span>{WAIT_REASONS[item.waitReason || "LIVE"] || item.waitReason}</span></div>
                    {item.queue && <div className="text-xs text-muted-foreground">
                      <span>Queued</span>: {item.queue.pending} · <span>Failed</span>: {item.queue.failed} · <span>Unconfirmed sends</span>: {item.queue.unconfirmed || 0} · <span>Last successful archive</span>: {item.queue.lastSuccessAt ? new Date(item.queue.lastSuccessAt).toLocaleString() : "—"}
                      {item.queue.oldestPendingAt > 0 && <div><span>Oldest pending message</span>: {new Date(item.queue.oldestPendingAt).toLocaleString()}</div>}
                      {item.queue.nextAttemptAt && item.queue.nextAttemptAt > Date.now() ? <div><span>Next attempt</span>: {new Date(item.queue.nextAttemptAt).toLocaleString()}</div> : null}
                    </div>}
                    {item.queue?.firstRecordId && (["UNCONFIRMED", "PREDECESSOR_FAILED"].includes(item.waitReason || "") || (item.queue.firstStatus === "UNKNOWN" || (item.queue.firstStatus === "FAILED" && item.rule.strictOrder))) && <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-destructive"><span>Blocked since</span><span>{item.queue.firstUpdatedAt ? new Date(item.queue.firstUpdatedAt).toLocaleString() : "—"}</span><Button size="sm" variant="outline" onClick={() => setResolving(item.queue!.firstRecordId!)}>Resolve blocked delivery</Button></div>}
                    {item.syncError && (
                      <div className="max-w-xl truncate text-destructive" title={item.syncError}>
                        {item.syncError}
                      </div>
                    )}
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={!item.enabled}
                      onClick={() => {
                        setHistoryLimit("ALL");
                        setHistoryRule(item);
                      }}
                    >
                      <History data-icon="inline-start" /> Archive history
                    </Button>
                    {!item.targetDownloadEnabled && (
                      <Button variant="outline" size="sm" asChild>
                        <Link
                          href={`/accounts?id=${item.telegramId}&chatId=${item.targetChatId}`}
                        >
                          <Settings2 data-icon="inline-start" /> Configure
                          download
                        </Link>
                      </Button>
                    )}
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={deleteBusy || bulkBusy}
                      onClick={() => void toggleRule(item)}
                    >
                      {item.enabled ? (
                        <Pause data-icon="inline-start" />
                      ) : (
                        <Play data-icon="inline-start" />
                      )}
                      {item.enabled ? "Pause" : "Resume"}
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={deleteBusy || bulkBusy}
                      onClick={() => openEdit(item)}
                    >
                      <Settings2 data-icon="inline-start" /> Edit
                    </Button>
                    <Button variant="destructive" size="sm" disabled={deleteBusy || bulkBusy} onClick={() => { setDeleteSelection([]); setDeleting(item); }}><Trash2 /> Delete rule</Button>
                  </div>
                </CardContent>
              </Card>
            ))
          )}
        </TabsContent>

        <TabsContent value="history" className="mt-4">
          <Card>
            <CardContent className="overflow-x-auto p-0">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Route</TableHead>
                    <TableHead>Progress</TableHead>
                    <TableHead>Daily quota</TableHead>
                    <TableHead>Matched / queued</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>Error</TableHead>
                    <TableHead className="w-56" />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(historyJobs ?? []).map((job) => (
                    <TableRow key={job.id}>
                      <TableCell>
                        <span translate="no">{job.sourceChatName}</span>
                        <span className="mx-1 text-muted-foreground">→</span>
                        <span translate="no">{job.targetChatName}</span>
                        <div className="mt-1 text-xs text-muted-foreground">
                          {job.archiveMode} ·{" "}
                          {job.topicMode === "PRESERVE"
                            ? "Preserve topics"
                            : job.topicMode === "MERGE"
                              ? "Merge topics"
                              : "Unknown topic mode"}
                        </div>
                      </TableCell>
                      <TableCell className="whitespace-nowrap tabular-nums">
                        <div>
                          {job.scannedCount} /{" "}
                          {job.scanMode === "ALL" ? "All history" : job.maxMessages}
                        </div>
                        {job.topicCount > 0 && (
                          <div className="text-xs text-muted-foreground">
                            Topic {Math.min(job.topicIndex + 1, job.topicCount)} /{" "}
                            {job.topicCount}
                          </div>
                        )}
                      </TableCell>
                      <TableCell className="whitespace-nowrap tabular-nums">
                        <div className="flex items-center gap-1.5 font-medium">
                          <span>
                            {job.dailyDate === new Date().toISOString().slice(0, 10)
                              ? job.dailyForwardedCount
                              : 0}
                          </span>
                          <span className="text-muted-foreground">/</span>
                          <span className="text-muted-foreground">
                            {job.dailyLimit === 0 ? "Unlimited" : `${job.dailyLimit} items/day`}
                          </span>
                        </div>
                        {job.dailyLimit > 0 &&
                          (job.dailyDate === new Date().toISOString().slice(0, 10)
                            ? job.dailyForwardedCount
                            : 0) >= job.dailyLimit &&
                          ["RUNNING", "PENDING"].includes(job.status) && (
                            <Badge
                              variant="secondary"
                              className="mt-1 border-amber-500/30 bg-amber-500/10 text-[10px] text-amber-600 dark:text-amber-400 py-0 px-1"
                            >
                              Daily quota reached
                            </Badge>
                          )}
                      </TableCell>
                      <TableCell className="whitespace-nowrap tabular-nums">
                        {job.matchedCount} / {job.queuedCount}
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-col items-start gap-1">
                          <Badge variant="outline">{job.status}</Badge>
                          {job.stage && job.stage !== job.status && (
                            <span className="text-xs text-muted-foreground">
                              {job.stage}
                            </span>
                          )}
                          {job.completionReason && (
                            <span className="text-xs text-muted-foreground">
                              {job.completionReason === "HISTORY_END"
                                ? "History end reached"
                                : "Limit reached"}
                            </span>
                          )}
                        </div>
                      </TableCell>
                      <TableCell
                        className="max-w-64 truncate"
                        title={job.lastError}
                      >
                        {job.lastError || "—"}
                      </TableCell>
                      <TableCell>
                        <div className="flex justify-end gap-1">
                          <Button
                            variant="ghost"
                            size="sm"
                            title="Adjust daily quota"
                            onClick={() => {
                              setAdjustLimitJob(job);
                              if (
                                job.dailyLimit === 200 ||
                                job.dailyLimit === 500 ||
                                job.dailyLimit === 1000 ||
                                job.dailyLimit === 0
                              ) {
                                setAdjustLimitType(String(job.dailyLimit) as any);
                              } else {
                                setAdjustLimitType("custom");
                                setAdjustCustomLimit(String(job.dailyLimit));
                              }
                            }}
                          >
                            <Settings2 /> Quota
                          </Button>
                          {["PENDING", "RUNNING"].includes(job.status) && (
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => void historyAction(job, "pause")}
                            >
                              <Pause /> Pause
                            </Button>
                          )}
                          {["PAUSED", "FAILED"].includes(job.status) && (
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => void historyAction(job, "resume")}
                            >
                              <Play /> Resume
                            </Button>
                          )}
                          {[
                            "PENDING",
                            "RUNNING",
                            "DRAINING",
                            "PAUSED",
                            "FAILED",
                          ].includes(job.status) && (
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => void historyAction(job, "cancel")}
                            >
                              Cancel
                            </Button>
                          )}
                          {["COMPLETED", "CANCELLED", "FAILED"].includes(
                            job.status,
                          ) && (
                            <Button
                              variant="ghost"
                              size="icon"
                              aria-label="Delete task"
                              onClick={() => void historyAction(job, "delete")}
                            >
                              <Trash2 />
                            </Button>
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                  {(historyJobs ?? []).length === 0 && (
                    <TableRow>
                      <TableCell
                        colSpan={7}
                        className="py-8 text-center text-muted-foreground"
                      >
                        No historical archive tasks yet.
                      </TableCell>
                    </TableRow>
                  )}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="records" className="mt-4">
          <label className="mb-3 flex items-center gap-2 text-sm"><span>Record destination</span><select className="max-w-full rounded border bg-background p-2" aria-label="Record destination" value={recordRoute} onChange={event => setRecordRoute(event.target.value)}>
            <option value="ALL">All destinations</option>{recordRoute !== "ALL" && !overview.rules.some(rule => ruleKey(rule) === recordRoute) && <option value={recordRoute}>Deleted destination</option>}{overview.rules.map(rule => <option key={ruleKey(rule)} value={ruleKey(rule)}>{rule.accountName}: {rule.sourceChatName} → {rule.targetChatName}</option>)}
          </select></label>
          <p className="mb-3 text-xs text-muted-foreground">Batch retry excludes unconfirmed sends. Review and retry those individually.</p>
          <Card>
            <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between border-b px-4 py-3 sm:px-6">
              <div className="flex flex-wrap items-center gap-3">
                <div className="text-sm font-medium text-muted-foreground">
                  Archive delivery logs & queue history
                </div>
                <div className="flex items-center gap-2">
                  <Select
                    value={recordStatusFilter}
                    onValueChange={setRecordStatusFilter}
                  >
                    <SelectTrigger className="h-8 min-w-[150px] text-xs">
                      <Filter className="mr-1.5 h-3.5 w-3.5 text-muted-foreground" />
                      <SelectValue placeholder="Filter status" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="ALL">
                        All ({overview?.statistics?.total ?? 0})
                      </SelectItem>
                      <SelectItem value="FAILED">
                        Failed ({overview?.statistics?.failed ?? 0})
                      </SelectItem>
                      <SelectItem value="UNKNOWN">Unconfirmed sends</SelectItem>
                      <SelectItem value="CANCELLED">Cancelled</SelectItem>
                      <SelectItem value="SKIPPED">
                        Skipped ({overview?.statistics?.skipped ?? 0})
                      </SelectItem>
                      <SelectItem value="COMPLETED">
                        Completed ({overview?.statistics?.completed ?? 0})
                      </SelectItem>
                      <SelectItem value="PENDING">
                        Pending ({overview?.statistics?.pending ?? 0})
                      </SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => void Promise.all([reloadOverview(), reloadRecords()])}
                >
                  <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
                  Refresh
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => void retryAllFailed()}
                >
                  <RefreshCw className="mr-1.5 h-3.5 w-3.5 text-amber-500" />
                  Retry Failed
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-destructive hover:bg-destructive/10"
                  disabled={!["ALL", "COMPLETED", "SKIPPED", "CANCELLED"].includes(recordStatusFilter)}
                  title="Clear only completed, skipped and cancelled records. Pending and unresolved records are retained."
                  onClick={() => void clearRecords()}
                >
                  <Trash2 className="mr-1.5 h-3.5 w-3.5" />
                  {recordStatusFilter !== "ALL"
                    ? `Clear ${recordStatusFilter}`
                    : "Clear Logs"}
                </Button>
              </div>
            </CardHeader>
            <CardContent className="overflow-x-auto p-0">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Source</TableHead>
                    <TableHead>Destination</TableHead>
                    <TableHead>Mode</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>Time</TableHead>
                    <TableHead>Error</TableHead>
                    <TableHead className="w-20" />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(records ?? []).length === 0 ? (
                    <TableRow>
                      <TableCell
                        colSpan={7}
                        className="h-28 text-center text-muted-foreground"
                      >
                        No archive records found for this status.
                      </TableCell>
                    </TableRow>
                  ) : (
                    (records ?? []).map((record) => (
                      <TableRow key={record.id}>
                        <TableCell>
                          <span translate="no">{record.sourceChatName}</span>
                          {record.sourceTopicId ? (
                            <span className="ml-1 text-xs text-muted-foreground">
                              {record.sourceTopicName || "Topic"} #{record.sourceTopicId}
                            </span>
                          ) : null}
                          <span className="ml-1 text-xs text-muted-foreground">
                            #{record.sourceMessageId}
                          </span>
                        </TableCell>
                        <TableCell>
                          <span translate="no">{record.targetChatName}</span>
                          {record.targetTopicId ? (
                            <span className="ml-1 text-xs text-muted-foreground">
                              {record.targetTopicName || "Topic"} #{record.targetTopicId}
                            </span>
                          ) : null}
                          {record.targetMessageId ? (
                            <span className="ml-1 text-xs text-muted-foreground">
                              #{record.targetMessageId}
                            </span>
                          ) : null}
                        </TableCell>
                        <TableCell>
                          <div>{record.mode}</div>
                          <div className="text-xs text-muted-foreground">
                            {record.topicMode === "PRESERVE"
                              ? "Preserve topics"
                              : "Merge topics"}
                          </div>
                          <div className="text-xs text-muted-foreground">
                            {!record.historyJobId
                              ? "Live"
                              : record.historyJobId.startsWith("sync:")
                                ? "Gap recovery"
                                : record.historyJobId.startsWith("live:")
                                  ? "Live held for order"
                                  : "Historical backfill"}
                          </div>
                          {record.topicMode === "PRESERVE" &&
                            record.sourceTopicId !== 0 &&
                            record.targetTopicId === 0 && (
                              <div className="text-xs text-destructive">
                                Target topic unresolved
                              </div>
                            )}
                        </TableCell>
                        <TableCell>
                          {getStatusBadge(record.status)}
                        </TableCell>
                        <TableCell className="whitespace-nowrap">
                          {new Date(record.updatedAt).toLocaleString()}
                        </TableCell>
                        <TableCell
                          className="max-w-64 truncate"
                          title={record.lastErrorMessage}
                        >
                          {record.lastErrorCode || "—"}
                        </TableCell>
                        <TableCell>
                          {["FAILED", "UNKNOWN"].includes(record.status) && <Button size="sm" variant="outline" onClick={() => setResolving(record.id)}>Resolve</Button>}
                          {["FAILED", "UNKNOWN", "SKIPPED"].includes(
                            record.status,
                          ) && (
                            <Button
                              variant="ghost"
                              size="icon"
                              aria-label="Retry"
                              onClick={() => void retry(record)}
                            >
                              <RefreshCw />
                            </Button>
                          )}
                        </TableCell>
                      </TableRow>
                    ))
                  )}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      <ArchiveRecoveryDialog recordId={resolving} onClose={() => setResolving(null)} onChanged={() => { void reloadOverview(); void reloadRecords(); void reloadHistory(); }} />
      <Dialog open={!!retrying} onOpenChange={open => { if (!open) setRetrying(null); }}>
        <DialogContent><DialogHeader><DialogTitle>Retry unconfirmed send?</DialogTitle><DialogDescription>Telegram may already have delivered this message. Check the destination before retrying to avoid duplicates.</DialogDescription></DialogHeader>
          <DialogFooter><Button variant="outline" onClick={() => setRetrying(null)}>Cancel</Button><Button variant="destructive" onClick={() => { if (retrying) void retry(retrying, true); }}>Confirm retry</Button></DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog open={!!deleting} onOpenChange={open => { if (!open && !deleteBusy) setDeleting(null); }}>
        <DialogContent><DialogHeader><DialogTitle>Delete archive rule?</DialogTitle>
          <DialogDescription>This stops this destination and cancels its history tasks and unsent messages. Existing Telegram messages and completed records are kept. A send already submitted to Telegram may still finish.</DialogDescription>
        </DialogHeader>
        <p>{deleting?.sourceChatName} → {deleting?.targetChatName}</p>
        {deleteSelection.length > 1 && <ul className="max-h-48 overflow-y-auto text-sm">{deleteSelection.map(rule => <li key={ruleKey(rule)}>{rule.sourceChatName} → {rule.targetChatName}</li>)}</ul>}
        <p><span>Queued</span>: {deleteTargets.reduce((total, rule) => total + (rule.queue?.pending || 0), 0)} · <span>History tasks</span>: {deleteTargets.reduce((total, rule) => total + (rule.queue?.historyTasks || 0), 0)}</p>
        <DialogFooter><Button variant="outline" disabled={deleteBusy} onClick={() => setDeleting(null)}>Cancel</Button><Button variant="destructive" disabled={deleteBusy} onClick={() => void deleteRule()}>Confirm delete rule</Button></DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>
              {editing ? "Edit cloud archive rule" : "New cloud archive rule"}
            </DialogTitle>
            <DialogDescription>
              New messages are archived automatically. Historical messages can
              be queued separately after the rule is saved.
            </DialogDescription>
          </DialogHeader>

          <div className="grid gap-5 py-2">
            <div className="grid gap-2">
              <Label>Telegram account</Label>
              <Select
                value={draft.telegramId || undefined}
                disabled={Boolean(editing)}
                onValueChange={(telegramId) => setDraft(emptyDraft(telegramId))}
              >
                <SelectTrigger>
                  <SelectValue placeholder="Select account ..." />
                </SelectTrigger>
                <SelectContent>
                  {accounts.map((account) => (
                    <SelectItem key={account.id} value={account.id}>
                      <span translate="no">{account.name}</span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <div className="grid gap-2">
                <Label>Source chat</Label>
                <RuleChatPicker
                  accountId={draft.telegramId}
                  value={draft.sourceChatId}
                  disabled={Boolean(editing)}
                  onChange={(sourceChatId, chat) =>
                    setDraft({
                      ...draft,
                      sourceChatId,
                      sourceIsForum: Boolean(chat.isForum),
                      rule: {
                        ...draft.rule,
                        sourceTopicId: 0,
                        topicMode:
                          chat.isForum && draft.targetIsForum
                            ? "PRESERVE"
                            : "MERGE",
                      },
                    })
                  }
                />
                {draft.sourceIsForum && (
                  <RuleTopicPicker
                    accountId={draft.telegramId}
                    chatId={draft.sourceChatId}
                    value={String(draft.rule.sourceTopicId || "")}
                    allowAll
                    allowClosed
                    onChange={(sourceTopicId) =>
                      setDraft({
                        ...draft,
                        rule: { ...draft.rule, sourceTopicId },
                      })
                    }
                  />
                )}
              </div>
              <div className="grid gap-2">
                <Label>Destination chat</Label>
                <RuleChatPicker
                  accountId={draft.telegramId}
                  value={String(draft.rule.targetChatId || "")}
                  onChange={(targetChatId, chat) =>
                    setDraft({
                      ...draft,
                      targetIsForum: Boolean(chat.isForum),
                      rule: {
                        ...draft.rule,
                        targetChatId,
                        targetTopicId: 0,
                        topicMode:
                          draft.sourceIsForum && chat.isForum
                            ? "PRESERVE"
                            : "MERGE",
                      },
                    })
                  }
                />
                {draft.targetIsForum && draft.rule.topicMode === "MERGE" && (
                  <RuleTopicPicker
                    accountId={draft.telegramId}
                    chatId={String(draft.rule.targetChatId || "")}
                    value={String(draft.rule.targetTopicId || "")}
                    onChange={(targetTopicId) =>
                      setDraft({
                        ...draft,
                        rule: { ...draft.rule, targetTopicId },
                      })
                    }
                  />
                )}
              </div>
            </div>

            {draft.sourceIsForum && draft.targetIsForum && (
              <div className="grid gap-2">
                <Label>Topic organization</Label>
                <Select
                  value={draft.rule.topicMode}
                  onValueChange={(topicMode: "MERGE" | "PRESERVE") =>
                    setDraft({
                      ...draft,
                      rule: { ...draft.rule, topicMode, targetTopicId: 0 },
                    })
                  }
                >
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="PRESERVE">Preserve source topics</SelectItem>
                    <SelectItem value="MERGE">Merge into one destination</SelectItem>
                  </SelectContent>
                </Select>
                <p className="text-xs text-muted-foreground">
                  Preserve creates one durable destination topic for every source
                  topic, even when names are duplicated.
                </p>
                {draft.rule.topicMode === "PRESERVE" && (
                  <p className="text-xs text-muted-foreground">
                    Telegram can still display them together when the destination is set to View as messages. Switch the destination group to View as topics to see the topic list.
                  </p>
                )}
              </div>
            )}

            <div className="grid gap-4 sm:grid-cols-2">
              <div className="grid gap-2">
                <Label>Archive mode</Label>
                <Select
                  value={draft.rule.mode}
                  onValueChange={(mode: ArchiveMode) =>
                    setDraft({
                      ...draft,
                      rule: { ...draft.rule, mode },
                    })
                  }
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="COPY">
                      Copy archive (recommended)
                    </SelectItem>
                    <SelectItem value="FORWARD">Forward with source</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-2">
                <Label>Content scope</Label>
                <Select
                  value={draft.rule.scope}
                  onValueChange={(scope: ArchiveScope) =>
                    setDraft({
                      ...draft,
                      rule: { ...draft.rule, scope },
                    })
                  }
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ALL_MESSAGES">All messages</SelectItem>
                    <SelectItem value="MEDIA_ONLY">Media only</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>

            <div className="grid gap-4 rounded-lg border p-4">
              <div className="grid gap-2">
                <Label>Initial synchronization</Label>
                <Select
                  value={draft.rule.initialSyncMode}
                  disabled={Boolean(editing)}
                  onValueChange={(initialSyncMode: "NOW" | "FULL") =>
                    setDraft({
                      ...draft,
                      rule: {
                        ...draft.rule,
                        initialSyncMode,
                        strictOrder:
                          initialSyncMode === "FULL"
                            ? true
                            : draft.rule.strictOrder,
                      },
                    })
                  }
                >
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="NOW">Start with new messages</SelectItem>
                    <SelectItem value="FULL">Mirror all available history</SelectItem>
                  </SelectContent>
                </Select>
                <p className="text-xs text-muted-foreground">
                  Full mirror creates an all-history task after this new rule is saved.
                  Existing archive records are reused, so restarts do not download or send them again.
                </p>
              </div>
              <div className="flex items-center justify-between gap-4">
                <div>
                  <Label>Strict chronological order</Label>
                  <p className="text-xs text-muted-foreground">
                    Hold newer messages until history or an outage gap is caught up,
                    then release the route oldest first.
                  </p>
                  <p className="text-xs text-muted-foreground">
                    Retries and unconfirmed deliveries hold later messages on this route. Pausing history releases this hold.
                  </p>
                </div>
                <Switch
                  checked={draft.rule.strictOrder}
                  onCheckedChange={(strictOrder) =>
                    setDraft({
                      ...draft,
                      rule: {
                        ...draft.rule,
                        strictOrder,
                        recoveryEnabled: strictOrder
                          ? true
                          : draft.rule.recoveryEnabled,
                      },
                    })
                  }
                />
              </div>
              <div className="flex items-center justify-between gap-4">
                <div>
                  <Label>Recover missed messages</Label>
                  <p className="text-xs text-muted-foreground">
                    Persist a Telegram cursor and scan only the missing interval after
                    a restart, network outage, or temporary container stop.
                  </p>
                </div>
                <Switch
                  checked={draft.rule.recoveryEnabled}
                  onCheckedChange={(recoveryEnabled) =>
                    setDraft({
                      ...draft,
                      rule: {
                        ...draft.rule,
                        recoveryEnabled,
                        strictOrder: recoveryEnabled
                          ? draft.rule.strictOrder
                          : false,
                      },
                    })
                  }
                />
              </div>
            </div>

            {draft.rule.scope === "MEDIA_ONLY" && (
              <div className="grid gap-2">
                <Label>File types</Label>
                <div className="flex flex-wrap gap-2">
                  {FILE_TYPES.map((type) => {
                    const selected = draft.rule.fileTypes.includes(type);
                    return (
                      <Button
                        key={type}
                        type="button"
                        size="sm"
                        variant={selected ? "default" : "outline"}
                        onClick={() =>
                          setDraft({
                            ...draft,
                            rule: {
                              ...draft.rule,
                              fileTypes: selected
                                ? draft.rule.fileTypes.filter(
                                    (value) => value !== type,
                                  )
                                : [...draft.rule.fileTypes, type],
                            },
                          })
                        }
                      >
                        {type}
                      </Button>
                    );
                  })}
                </div>
                <p className="text-xs text-muted-foreground">
                  No selection means every supported media type.
                </p>
              </div>
            )}

            <div className="grid gap-2">
              <Label htmlFor="archive-query">Query keyword</Label>
              <Input
                id="archive-query"
                value={draft.rule.query}
                placeholder="Optional text or caption keyword"
                onChange={(event) =>
                  setDraft({
                    ...draft,
                    rule: { ...draft.rule, query: event.target.value },
                  })
                }
              />
            </div>

            <div className="grid gap-2">
              <Label htmlFor="archive-filter">Filter expression</Label>
              <Textarea
                id="archive-filter"
                value={draft.rule.filterExpr}
                placeholder="Optional advanced message filter"
                onChange={(event) =>
                  setDraft({
                    ...draft,
                    rule: { ...draft.rule, filterExpr: event.target.value },
                  })
                }
              />
            </div>

            <div className="grid gap-3 rounded-lg border p-4">
              <div>
                <Label className="text-base font-semibold">Media & File Filtering</Label>
                <p className="text-xs text-muted-foreground">
                  Filter messages by file size and extensions (leave empty for unlimited).
                </p>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div className="grid gap-1.5">
                  <Label htmlFor="archive-min-size">Min file size (MB)</Label>
                  <Input
                    id="archive-min-size"
                    type="number"
                    min="0"
                    placeholder="e.g. 10"
                    value={
                      draft.rule.minSize
                        ? Number((draft.rule.minSize / (1024 * 1024)).toFixed(2))
                        : ""
                    }
                    onChange={(e) => {
                      const val = e.target.value;
                      setDraft({
                        ...draft,
                        rule: {
                          ...draft.rule,
                          minSize: val ? Math.round(Number(val) * 1024 * 1024) : 0,
                        },
                      });
                    }}
                  />
                </div>
                <div className="grid gap-1.5">
                  <Label htmlFor="archive-max-size">Max file size (MB)</Label>
                  <Input
                    id="archive-max-size"
                    type="number"
                    min="0"
                    placeholder="e.g. 2048"
                    value={
                      draft.rule.maxSize
                        ? Number((draft.rule.maxSize / (1024 * 1024)).toFixed(2))
                        : ""
                    }
                    onChange={(e) => {
                      const val = e.target.value;
                      setDraft({
                        ...draft,
                        rule: {
                          ...draft.rule,
                          maxSize: val ? Math.round(Number(val) * 1024 * 1024) : 0,
                        },
                      });
                    }}
                  />
                </div>
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor="archive-extensions">Allowed extensions</Label>
                <Input
                  id="archive-extensions"
                  placeholder="e.g. mp4, mkv, zip, rar"
                  value={draft.rule.extensions?.join(", ") ?? ""}
                  onChange={(e) => {
                    const exts = e.target.value
                      .split(/[,，\s]+/)
                      .map((s) => s.trim().replace(/^\./, ""))
                      .filter(Boolean);
                    setDraft({
                      ...draft,
                      rule: { ...draft.rule, extensions: exts },
                    });
                  }}
                />
                <p className="text-xs text-muted-foreground">
                  Comma separated. Only messages with matching file extensions will be archived.
                </p>
              </div>
            </div>

            {draft.rule.mode === "COPY" && (
              <div className="grid gap-4 rounded-lg border p-4">
                <div className="flex items-center justify-between gap-4">
                  <div>
                    <Label className="text-base font-semibold">Caption Cleaning & Replacement</Label>
                    <p className="text-xs text-muted-foreground">
                      Automatically sanitize spam, remove ads, replace keywords, and append signature.
                    </p>
                  </div>
                  <Switch
                    checked={draft.rule.cleanCaption}
                    onCheckedChange={(cleanCaption) =>
                      setDraft({
                        ...draft,
                        rule: { ...draft.rule, cleanCaption },
                      })
                    }
                  />
                </div>

                {draft.rule.cleanCaption && (
                  <div className="grid gap-3 pt-2">
                    <div className="flex items-center justify-between gap-4">
                      <div>
                        <Label>Strip links & URLs</Label>
                        <p className="text-xs text-muted-foreground">
                          Remove http(s):// and t.me/ invite links from captions.
                        </p>
                      </div>
                      <Switch
                        checked={draft.rule.stripLinks}
                        onCheckedChange={(stripLinks) =>
                          setDraft({
                            ...draft,
                            rule: { ...draft.rule, stripLinks },
                          })
                        }
                      />
                    </div>

                    <div className="flex items-center justify-between gap-4">
                      <div>
                        <Label>Strip @usernames</Label>
                        <p className="text-xs text-muted-foreground">
                          Remove @mentions and Telegram channel tags.
                        </p>
                      </div>
                      <Switch
                        checked={draft.rule.stripUsernames}
                        onCheckedChange={(stripUsernames) =>
                          setDraft({
                            ...draft,
                            rule: { ...draft.rule, stripUsernames },
                          })
                        }
                      />
                    </div>

                    <div className="grid gap-2">
                      <div className="flex items-center justify-between">
                        <Label>Keyword / Regex replacements</Label>
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={() => {
                            const list = [...(draft.rule.captionReplacements || [])];
                            list.push({ pattern: "", replacement: "" });
                            setDraft({
                              ...draft,
                              rule: { ...draft.rule, captionReplacements: list },
                            });
                          }}
                        >
                          <Plus className="mr-1 h-3.5 w-3.5" />
                          Add rule
                        </Button>
                      </div>
                      {(draft.rule.captionReplacements || []).map((cr, idx) => (
                        <div key={idx} className="flex items-center gap-2">
                          <Input
                            placeholder="Pattern / Regex"
                            value={cr.pattern}
                            onChange={(e) => {
                              const list = [...(draft.rule.captionReplacements || [])];
                              list[idx] = {
                                pattern: e.target.value,
                                replacement: list[idx]?.replacement ?? "",
                              };
                              setDraft({
                                ...draft,
                                rule: { ...draft.rule, captionReplacements: list },
                              });
                            }}
                          />
                          <Input
                            placeholder="Replace with"
                            value={cr.replacement}
                            onChange={(e) => {
                              const list = [...(draft.rule.captionReplacements || [])];
                              list[idx] = {
                                pattern: list[idx]?.pattern ?? "",
                                replacement: e.target.value,
                              };
                              setDraft({
                                ...draft,
                                rule: { ...draft.rule, captionReplacements: list },
                              });
                            }}
                          />
                          <Button
                            type="button"
                            variant="ghost"
                            size="icon"
                            onClick={() => {
                              const list = (draft.rule.captionReplacements || []).filter(
                                (_, i) => i !== idx,
                              );
                              setDraft({
                                ...draft,
                                rule: { ...draft.rule, captionReplacements: list },
                              });
                            }}
                          >
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        </div>
                      ))}
                      {(draft.rule.captionReplacements || []).length === 0 && (
                        <p className="text-xs text-muted-foreground">
                          No custom replacement rules defined.
                        </p>
                      )}
                    </div>

                    <div className="grid gap-2">
                      <Label htmlFor="archive-caption-suffix">Append caption signature / suffix</Label>
                      <Textarea
                        id="archive-caption-suffix"
                        placeholder="Optional footer text or watermark (e.g. Channel: @my_channel)"
                        value={draft.rule.captionSuffix || ""}
                        onChange={(e) =>
                          setDraft({
                            ...draft,
                            rule: { ...draft.rule, captionSuffix: e.target.value },
                          })
                        }
                      />
                    </div>
                  </div>
                )}
              </div>
            )}

            <div className="grid gap-3 rounded-lg border p-4">
              <div className="flex items-center justify-between gap-4">
                <div>
                  <Label>Keep media captions</Label>
                  <p className="text-xs text-muted-foreground">
                    Captions and their formatting remain on copied media.
                  </p>
                </div>
                <Switch
                  checked={draft.rule.preserveCaption}
                  onCheckedChange={(preserveCaption) =>
                    setDraft({
                      ...draft,
                      rule: { ...draft.rule, preserveCaption },
                    })
                  }
                />
              </div>
              <div className="flex items-center justify-between gap-4">
                <div>
                  <Label>Send silently</Label>
                  <p className="text-xs text-muted-foreground">
                    Do not notify destination channel subscribers.
                  </p>
                </div>
                <Switch
                  checked={draft.rule.disableNotification}
                  onCheckedChange={(disableNotification) =>
                    setDraft({
                      ...draft,
                      rule: { ...draft.rule, disableNotification },
                    })
                  }
                />
              </div>
              <div className="flex items-center justify-between gap-4">
                <div>
                  <Label>Rule enabled</Label>
                  <p className="text-xs text-muted-foreground">
                    Paused rules remain saved but do not process messages.
                  </p>
                </div>
                <Switch
                  checked={draft.enabled}
                  onCheckedChange={(enabled) => setDraft({ ...draft, enabled })}
                />
              </div>
            </div>
          </div>

          <DialogFooter className="flex-col gap-2 sm:flex-row">
            <Button
              variant="outline"
              disabled={!validDraft || checking !== null}
              onClick={() => void validate(false)}
            >
              {checking === "validate" ? (
                <Loader2 className="animate-spin" />
              ) : (
                <ShieldCheck />
              )}
              Check permissions
            </Button>
            <Button
              variant="outline"
              disabled={!validDraft || checking !== null}
              onClick={() => void validate(true)}
            >
              {checking === "test" ? (
                <Loader2 className="animate-spin" />
              ) : (
                <Send />
              )}
              Send real test
            </Button>
            <Button
              disabled={!validDraft || saving}
              onClick={() => void save()}
            >
              {saving ? <Loader2 className="animate-spin" /> : <Copy />}
              Save rule
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={historyRule !== null}
        onOpenChange={(open) => !open && setHistoryRule(null)}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Archive historical messages</DialogTitle>
            <DialogDescription>
              The task reads older messages in small pages. Live delivery remains
              independent unless strict chronological order is enabled. Media stays
              inside Telegram.
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-3 py-2">
            <p className="text-sm">
              <span translate="no">{historyRule?.sourceChatName}</span>
              <span className="mx-2 text-muted-foreground">→</span>
              <span translate="no">{historyRule?.targetChatName}</span>
            </p>
            <div className="grid gap-2">
              <Label>History range</Label>
              <Select value={historyLimit} onValueChange={setHistoryLimit}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ALL">All history</SelectItem>
                  <SelectItem value="100">100 items</SelectItem>
                  <SelectItem value="500">500 items</SelectItem>
                  <SelectItem value="1000">1,000 items</SelectItem>
                  <SelectItem value="5000">5,000 items</SelectItem>
                  <SelectItem value="10000">10,000 items</SelectItem>
                  <SelectItem value="100000">100,000 items</SelectItem>
                </SelectContent>
              </Select>
            </div>

            <div className="grid gap-2 rounded-lg border p-3 bg-muted/20">
              <div className="flex items-center justify-between">
                <Label className="text-sm font-medium">Daily Quota</Label>
                <span className="text-xs text-muted-foreground font-mono">
                  {historyDailyLimitType === "0"
                    ? "Unlimited"
                    : historyDailyLimitType === "custom"
                      ? `${historyCustomDailyLimit || 0} items/day`
                      : `${historyDailyLimitType} items/day`}
                </span>
              </div>
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                <Button
                  type="button"
                  size="sm"
                  variant={historyDailyLimitType === "200" ? "default" : "outline"}
                  className="flex flex-col h-auto py-1.5 px-2 text-left items-start"
                  onClick={() => setHistoryDailyLimitType("200")}
                >
                  <span className="font-semibold text-xs">200 items/day</span>
                  <span className="text-[10px] opacity-80">Safe (Recommended for new accounts)</span>
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant={historyDailyLimitType === "500" ? "default" : "outline"}
                  className="flex flex-col h-auto py-1.5 px-2 text-left items-start"
                  onClick={() => setHistoryDailyLimitType("500")}
                >
                  <span className="font-semibold text-xs">500 items/day</span>
                  <span className="text-[10px] opacity-80">Balanced (Recommended for daily use)</span>
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant={historyDailyLimitType === "1000" ? "default" : "outline"}
                  className="flex flex-col h-auto py-1.5 px-2 text-left items-start"
                  onClick={() => setHistoryDailyLimitType("1000")}
                >
                  <span className="font-semibold text-xs">1000 items/day</span>
                  <span className="text-[10px] opacity-80">Fast (Older accounts / Premium)</span>
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant={historyDailyLimitType === "0" ? "default" : "outline"}
                  className="flex flex-col h-auto py-1.5 px-2 text-left items-start"
                  onClick={() => setHistoryDailyLimitType("0")}
                >
                  <span className="font-semibold text-xs">Unlimited</span>
                  <span className="text-[10px] opacity-80">Unlimited (Maximum speed)</span>
                </Button>
              </div>
              <div className="flex items-center gap-2 mt-1">
                <Button
                  type="button"
                  size="sm"
                  variant={historyDailyLimitType === "custom" ? "default" : "outline"}
                  className="shrink-0 text-xs h-8"
                  onClick={() => setHistoryDailyLimitType("custom")}
                >
                  Custom limit
                </Button>
                {historyDailyLimitType === "custom" && (
                  <div className="flex items-center gap-1.5 flex-1">
                    <Input
                      type="number"
                      min="0"
                      max="50000"
                      placeholder="Daily maximum items"
                      value={historyCustomDailyLimit}
                      onChange={(e) => setHistoryCustomDailyLimit(e.target.value)}
                      className="h-8 text-xs tabular-nums"
                    />
                    <span className="text-xs text-muted-foreground shrink-0">items/day</span>
                  </div>
                )}
              </div>
              <p className="text-[11px] text-muted-foreground leading-relaxed mt-1">
                Automatically pauses when the daily quota is reached and resumes at 00:00 next day to prevent rate limiting or temporary bans from Telegram.
              </p>
            </div>

            <p className="text-xs text-muted-foreground">
              Historical messages are staged while scanning, then released oldest
              first. Existing archive records are skipped, so rerunning does not
              duplicate messages. Pausing this task does not pause ordinary live
              delivery; pausing intentionally releases the strict hold.
            </p>
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setHistoryRule(null)}
            >
              Cancel
            </Button>
            <Button disabled={historySaving} onClick={() => void createHistory()}>
              {historySaving ? <Loader2 className="animate-spin" /> : <History />}
              Start task
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={adjustLimitJob !== null}
        onOpenChange={(open) => !open && setAdjustLimitJob(null)}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Adjust Daily Quota</DialogTitle>
            <DialogDescription>
              Adjust the daily transfer limit for historical tasks anytime. If the limit was reached today, increasing it will automatically resume the task.
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-3 py-2">
            <p className="text-sm">
              <span translate="no">{adjustLimitJob?.sourceChatName}</span>
              <span className="mx-2 text-muted-foreground">→</span>
              <span translate="no">{adjustLimitJob?.targetChatName}</span>
            </p>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              <Button
                type="button"
                size="sm"
                variant={adjustLimitType === "200" ? "default" : "outline"}
                className="flex flex-col h-auto py-1.5 px-2 text-left items-start"
                onClick={() => setAdjustLimitType("200")}
              >
                <span className="font-semibold text-xs">200 items/day</span>
                <span className="text-[10px] opacity-80">Safe</span>
              </Button>
              <Button
                type="button"
                size="sm"
                variant={adjustLimitType === "500" ? "default" : "outline"}
                className="flex flex-col h-auto py-1.5 px-2 text-left items-start"
                onClick={() => setAdjustLimitType("500")}
              >
                <span className="font-semibold text-xs">500 items/day</span>
                <span className="text-[10px] opacity-80">Balanced</span>
              </Button>
              <Button
                type="button"
                size="sm"
                variant={adjustLimitType === "1000" ? "default" : "outline"}
                className="flex flex-col h-auto py-1.5 px-2 text-left items-start"
                onClick={() => setAdjustLimitType("1000")}
              >
                <span className="font-semibold text-xs">1000 items/day</span>
                <span className="text-[10px] opacity-80">Fast</span>
              </Button>
              <Button
                type="button"
                size="sm"
                variant={adjustLimitType === "0" ? "default" : "outline"}
                className="flex flex-col h-auto py-1.5 px-2 text-left items-start"
                onClick={() => setAdjustLimitType("0")}
              >
                <span className="font-semibold text-xs">Unlimited</span>
                <span className="text-[10px] opacity-80">Unlimited</span>
              </Button>
            </div>
            <div className="flex items-center gap-2 mt-1">
              <Button
                type="button"
                size="sm"
                variant={adjustLimitType === "custom" ? "default" : "outline"}
                className="shrink-0 text-xs h-8"
                onClick={() => setAdjustLimitType("custom")}
              >
                Custom limit
              </Button>
              {adjustLimitType === "custom" && (
                <div className="flex items-center gap-1.5 flex-1">
                  <Input
                    type="number"
                    min="0"
                    max="50000"
                    placeholder="Daily maximum items"
                    value={adjustCustomLimit}
                    onChange={(e) => setAdjustCustomLimit(e.target.value)}
                    className="h-8 text-xs tabular-nums"
                  />
                  <span className="text-xs text-muted-foreground shrink-0">items/day</span>
                </div>
              )}
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setAdjustLimitJob(null)}>
              Cancel
            </Button>
            <Button disabled={adjustSaving} onClick={() => void saveDailyLimit()}>
              {adjustSaving ? <Loader2 className="animate-spin mr-1 h-4 w-4" /> : null}
              Save Settings
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
