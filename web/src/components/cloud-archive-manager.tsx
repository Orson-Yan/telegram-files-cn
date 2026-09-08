"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import useSWR from "swr";
import {
  AlertTriangle,
  CheckCircle2,
  CloudUpload,
  Copy,
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
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
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
import { RuleTopicPicker } from "@/components/rule-topic-picker";
import { useTelegramAccount } from "@/hooks/use-telegram-account";
import { useToast } from "@/hooks/use-toast";
import { normalizeAuto } from "@/lib/automation";
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
  enabled: boolean;
  rule: AutoArchiveRule;
};

const FILE_TYPES = ["photo", "video", "audio", "file"] as const;

function emptyDraft(telegramId = ""): RuleDraft {
  return {
    telegramId,
    sourceChatId: "",
    enabled: true,
    rule: {
      sourceTopicId: 0,
      targetChatId: 0,
      targetTopicId: 0,
      mode: "COPY",
      scope: "ALL_MESSAGES",
      fileTypes: [],
      query: "",
      filterExpr: "",
      preserveCaption: true,
      disableNotification: true,
    },
  };
}

function toDraft(rule: CloudArchiveRuleOverview): RuleDraft {
  return {
    telegramId: rule.telegramId,
    sourceChatId: rule.sourceChatId,
    enabled: rule.enabled,
    rule: {
      ...rule.rule,
      sourceTopicId: String(rule.rule.sourceTopicId || ""),
      targetChatId: String(rule.targetChatId),
      targetTopicId: String(rule.rule.targetTopicId || ""),
    },
  };
}

async function currentAuto(telegramId: string, chatId: string) {
  const chats = await request<TelegramChat[]>(
    `/telegram/${telegramId}/chats?query=&archived=false&chatId=${chatId}`,
  );
  return normalizeAuto(chats.find((chat) => chat.id === chatId)?.auto);
}

async function saveDraft(draft: RuleDraft) {
  const auto = await currentAuto(draft.telegramId, draft.sourceChatId);
  auto.archive = {
    enabled: draft.enabled,
    rule: {
      ...draft.rule,
      sourceTopicId: Number(draft.rule.sourceTopicId || 0),
      targetChatId: Number(draft.rule.targetChatId),
      targetTopicId: Number(draft.rule.targetTopicId || 0),
    },
  };
  await POST(
    `/${draft.telegramId}/file/update-auto-settings?telegramId=${draft.telegramId}&chatId=${draft.sourceChatId}`,
    auto,
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
      <CardContent className="flex items-center justify-between p-4">
        <div>
          <p className="text-xs text-muted-foreground">{label}</p>
          <p className="mt-1 text-2xl font-semibold tabular-nums">{value}</p>
        </div>
        <div className="rounded-full bg-muted p-2 text-muted-foreground">
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
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<RuleDraft>(() => emptyDraft());
  const [saving, setSaving] = useState(false);
  const [checking, setChecking] = useState<"validate" | "test" | null>(null);
  const [historyRule, setHistoryRule] =
    useState<CloudArchiveRuleOverview | null>(null);
  const [historyLimit, setHistoryLimit] = useState("1000");
  const [historySaving, setHistorySaving] = useState(false);

  const {
    data: overview,
    error,
    isLoading,
    mutate: reloadOverview,
  } = useSWR<CloudArchiveOverview>("/cloud-archive/overview", {
    refreshInterval: 5000,
  });
  const { data: records, mutate: reloadRecords } = useSWR<CloudArchiveRecord[]>(
    "/cloud-archive/records?limit=100",
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
    setEditing(false);
    setDraft(emptyDraft(accounts[0]?.id ?? ""));
    setDialogOpen(true);
  };

  const openEdit = (rule: CloudArchiveRuleOverview) => {
    setEditing(true);
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
      await saveDraft(draft);
      await Promise.all([reloadOverview(), reloadRecords()]);
      setDialogOpen(false);
      toast({ variant: "success", title: "Cloud archive rule saved" });
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

  const toggleRule = async (rule: CloudArchiveRuleOverview) => {
    try {
      await saveDraft({ ...toDraft(rule), enabled: !rule.enabled });
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

  const retry = async (record: CloudArchiveRecord) => {
    try {
      await POST(`/cloud-archive/records/${record.id}/retry`);
      await reloadRecords();
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

  const createHistory = async () => {
    if (!historyRule) return;
    setHistorySaving(true);
    try {
      await POST("/cloud-archive/history", {
        telegramId: historyRule.telegramId,
        sourceChatId: historyRule.sourceChatId,
        maxMessages: Number(historyLimit),
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

  const historyAction = async (
    job: CloudArchiveHistoryJob,
    action: "pause" | "resume" | "cancel",
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
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
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
          {overview.rules.length === 0 ? (
            <Card>
              <CardContent className="p-8 text-center text-muted-foreground">
                No cloud archive rules. Create one to copy new Telegram messages
                without downloading them first.
              </CardContent>
            </Card>
          ) : (
            overview.rules.map((item) => (
              <Card key={`${item.telegramId}:${item.sourceChatId}`}>
                <CardHeader className="pb-3">
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
                      </p>
                    </div>
                    <Badge variant={item.enabled ? "default" : "secondary"}>
                      {item.enabled ? "Running" : "Paused"}
                    </Badge>
                  </div>
                </CardHeader>
                <CardContent className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                  <div className="text-sm text-muted-foreground">
                    Target auto download:{" "}
                    {item.targetDownloadEnabled ? "Enabled" : "Disabled"}
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={!item.enabled}
                      onClick={() => {
                        setHistoryLimit("1000");
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
                      onClick={() => openEdit(item)}
                    >
                      <Settings2 data-icon="inline-start" /> Edit
                    </Button>
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
                    <TableHead>Matched / queued</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>Error</TableHead>
                    <TableHead className="w-44" />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(historyJobs ?? []).map((job) => (
                    <TableRow key={job.id}>
                      <TableCell>
                        <span translate="no">{job.sourceChatName}</span>
                        <span className="mx-1 text-muted-foreground">→</span>
                        <span translate="no">{job.targetChatName}</span>
                      </TableCell>
                      <TableCell className="whitespace-nowrap tabular-nums">
                        {job.scannedCount} / {job.maxMessages}
                      </TableCell>
                      <TableCell className="whitespace-nowrap tabular-nums">
                        {job.matchedCount} / {job.queuedCount}
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline">{job.status}</Badge>
                      </TableCell>
                      <TableCell
                        className="max-w-64 truncate"
                        title={job.lastError}
                      >
                        {job.lastError || "—"}
                      </TableCell>
                      <TableCell>
                        <div className="flex justify-end gap-1">
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
                          {["PENDING", "RUNNING", "PAUSED"].includes(
                            job.status,
                          ) && (
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => void historyAction(job, "cancel")}
                            >
                              Cancel
                            </Button>
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                  {(historyJobs ?? []).length === 0 && (
                    <TableRow>
                      <TableCell
                        colSpan={6}
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
          <Card>
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
                  {(records ?? []).map((record) => (
                    <TableRow key={record.id}>
                      <TableCell>
                        <span translate="no">{record.sourceChatName}</span>
                        {record.sourceTopicId ? (
                          <span className="ml-1 text-xs text-muted-foreground">
                            topic #{record.sourceTopicId}
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
                            topic #{record.targetTopicId}
                          </span>
                        ) : null}
                        {record.targetMessageId ? (
                          <span className="ml-1 text-xs text-muted-foreground">
                            #{record.targetMessageId}
                          </span>
                        ) : null}
                      </TableCell>
                      <TableCell>{record.mode}</TableCell>
                      <TableCell>
                        <Badge variant="outline">{record.status}</Badge>
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
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

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
                disabled={editing}
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
                  onChange={(sourceChatId) =>
                    setDraft({
                      ...draft,
                      sourceChatId,
                      rule: { ...draft.rule, sourceTopicId: 0 },
                    })
                  }
                />
                <RuleTopicPicker
                  accountId={draft.telegramId}
                  chatId={draft.sourceChatId}
                  value={String(draft.rule.sourceTopicId || "")}
                  allowAll
                  onChange={(sourceTopicId) =>
                    setDraft({
                      ...draft,
                      rule: { ...draft.rule, sourceTopicId },
                    })
                  }
                />
              </div>
              <div className="grid gap-2">
                <Label>Destination chat</Label>
                <RuleChatPicker
                  accountId={draft.telegramId}
                  value={String(draft.rule.targetChatId || "")}
                  onChange={(targetChatId) =>
                    setDraft({
                      ...draft,
                      rule: {
                        ...draft.rule,
                        targetChatId,
                        targetTopicId: 0,
                      },
                    })
                  }
                />
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
              </div>
            </div>

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
              The task reads older messages in small pages and feeds the same
              protected queue as new messages. Media stays inside Telegram.
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-3 py-2">
            <p className="text-sm">
              <span translate="no">{historyRule?.sourceChatName}</span>
              <span className="mx-2 text-muted-foreground">→</span>
              <span translate="no">{historyRule?.targetChatName}</span>
            </p>
            <div className="grid gap-2">
              <Label>Maximum messages to scan</Label>
              <Select value={historyLimit} onValueChange={setHistoryLimit}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="100">100</SelectItem>
                  <SelectItem value="500">500</SelectItem>
                  <SelectItem value="1000">1,000</SelectItem>
                  <SelectItem value="5000">5,000</SelectItem>
                  <SelectItem value="10000">10,000</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <p className="text-xs text-muted-foreground">
              Existing source-to-destination records are skipped, so rerunning
              a completed range does not duplicate archived messages.
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
    </div>
  );
}
