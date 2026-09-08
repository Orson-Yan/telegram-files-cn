"use client";

import { useEffect, useMemo, useState } from "react";
import useSWR from "swr";
import {
  CalendarDays,
  FolderInput,
  Loader2,
  Pause,
  Play,
  Plus,
  Search,
  Settings2,
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
import { Textarea } from "@/components/ui/textarea";
import { RuleChatPicker } from "@/components/rule-chat-picker";
import { RuleTopicPicker } from "@/components/rule-topic-picker";
import { useTelegramAccount } from "@/hooks/use-telegram-account";
import { useToast } from "@/hooks/use-toast";
import { normalizeAuto } from "@/lib/automation";
import { POST, request } from "@/lib/api";
import type {
  AutoTransferRule,
  DuplicationPolicy,
  LocalOrganizeOverview,
  LocalOrganizeRuleOverview,
  TelegramChat,
  TransferPolicy,
} from "@/lib/types";

type OrganizeDraft = {
  telegramId: string;
  sourceChatId: string;
  enabled: boolean;
  rule: AutoTransferRule;
};

type PreviewResponse = {
  total: number;
  items: Array<{
    uniqueId: string;
    fileName?: string;
    sourcePath: string;
    destinationPath: string;
    destinationExists: boolean;
  }>;
};

function emptyDraft(telegramId = ""): OrganizeDraft {
  return {
    telegramId,
    sourceChatId: "",
    enabled: true,
    rule: {
      transferHistory: false,
      sourceTopicId: 0,
      destination: "",
      transferPolicy: "GROUP_BY_DATE",
      duplicationPolicy: "RENAME",
      useCaptionName: false,
      extra: {
        timezone: "Asia/Shanghai",
        dateGrouping: "YEAR_MONTH",
        includeChatDirectory: true,
      },
    },
  };
}

function toDraft(rule: LocalOrganizeRuleOverview): OrganizeDraft {
  return {
    telegramId: rule.telegramId,
    sourceChatId: rule.sourceChatId,
    enabled: rule.enabled,
    rule: {
      ...rule.rule,
      extra: {
        timezone: "Asia/Shanghai",
        dateGrouping: "YEAR_MONTH",
        includeChatDirectory: true,
        ...rule.rule.extra,
      },
    },
  };
}

async function currentAuto(telegramId: string, chatId: string) {
  const chats = await request<TelegramChat[]>(
    `/telegram/${telegramId}/chats?query=&archived=false&chatId=${chatId}`,
  );
  return normalizeAuto(chats.find((chat) => chat.id === chatId)?.auto);
}

async function saveDraft(draft: OrganizeDraft) {
  const auto = await currentAuto(draft.telegramId, draft.sourceChatId);
  auto.transfer = { enabled: draft.enabled, rule: draft.rule };
  await POST(
    `/${draft.telegramId}/file/update-auto-settings?telegramId=${draft.telegramId}&chatId=${draft.sourceChatId}`,
    auto,
  );
}

export function LocalOrganizeManager() {
  const { getAccounts } = useTelegramAccount();
  const accounts = getAccounts("active");
  const { toast } = useToast();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<OrganizeDraft>(() => emptyDraft());
  const [saving, setSaving] = useState(false);
  const [previewing, setPreviewing] = useState(false);
  const [preview, setPreview] = useState<PreviewResponse | null>(null);

  const { data, error, isLoading, mutate } = useSWR<LocalOrganizeOverview>(
    "/local-organize/overview",
    { refreshInterval: 5000 },
  );

  useEffect(() => {
    if (!draft.telegramId && accounts.length > 0) {
      setDraft(emptyDraft(accounts[0]?.id));
    }
  }, [accounts, draft.telegramId]);

  const validDraft = useMemo(
    () =>
      Boolean(
        draft.telegramId && draft.sourceChatId && draft.rule.destination.trim(),
      ),
    [draft],
  );

  const openNew = () => {
    setEditing(false);
    setPreview(null);
    setDraft(emptyDraft(accounts[0]?.id ?? ""));
    setDialogOpen(true);
  };

  const openEdit = (rule: LocalOrganizeRuleOverview) => {
    setEditing(true);
    setPreview(null);
    setDraft(toDraft(rule));
    setDialogOpen(true);
  };

  const previewPaths = async () => {
    if (!validDraft) return;
    setPreviewing(true);
    try {
      const result = await POST("/local-organize/preview", {
        telegramId: draft.telegramId,
        sourceChatId: draft.sourceChatId,
        rule: draft.rule,
      });
      setPreview(result);
    } catch (failure) {
      toast({
        variant: "error",
        title: "Failed to preview local organization",
        description:
          failure instanceof Error ? failure.message : String(failure),
      });
    } finally {
      setPreviewing(false);
    }
  };

  const save = async () => {
    if (!validDraft) return;
    setSaving(true);
    try {
      await saveDraft(draft);
      await mutate();
      setDialogOpen(false);
      toast({ variant: "success", title: "Local organization rule saved" });
    } catch (failure) {
      toast({
        variant: "error",
        title: "Failed to save local organization rule",
        description:
          failure instanceof Error ? failure.message : String(failure),
      });
    } finally {
      setSaving(false);
    }
  };

  const toggle = async (rule: LocalOrganizeRuleOverview) => {
    try {
      await saveDraft({ ...toDraft(rule), enabled: !rule.enabled });
      await mutate();
    } catch (failure) {
      toast({
        variant: "error",
        title: "Failed to update local organization rule",
        description:
          failure instanceof Error ? failure.message : String(failure),
      });
    }
  };

  if (isLoading) {
    return (
      <div className="flex justify-center p-12">
        <Loader2 className="animate-spin" />
      </div>
    );
  }
  if (error || !data) {
    return (
      <Card>
        <CardContent className="p-6 text-destructive">
          Failed to load local organization rules
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="space-y-5">
      <Card>
        <CardContent className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className="font-medium">
              {data.rules.length} local organization rules
            </p>
            <p className="text-sm text-muted-foreground">
              These rules move completed downloads on the server. They do not
              forward Telegram messages.
            </p>
          </div>
          <Button onClick={openNew} disabled={accounts.length === 0}>
            <Plus data-icon="inline-start" /> New rule
          </Button>
        </CardContent>
      </Card>

      {data.rules.length === 0 ? (
        <Card>
          <CardContent className="p-8 text-center text-muted-foreground">
            No local organization rules.
          </CardContent>
        </Card>
      ) : (
        data.rules.map((item) => (
          <Card key={`${item.telegramId}:${item.sourceChatId}`}>
            <CardHeader className="pb-3">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <CardTitle className="flex items-center gap-2 text-base">
                    <span translate="no">{item.sourceChatName}</span>
                    {Number(item.rule.sourceTopicId || 0) !== 0 && (
                      <span className="text-xs text-muted-foreground">
                        topic #{item.rule.sourceTopicId}
                      </span>
                    )}
                    <span className="text-muted-foreground">→</span>
                    <span className="truncate" translate="no">
                      {item.rule.destination}
                    </span>
                  </CardTitle>
                  <p className="mt-1 text-sm text-muted-foreground">
                    Move files · {item.rule.transferPolicy} ·{" "}
                    {item.rule.duplicationPolicy}
                  </p>
                </div>
                <Badge variant={item.enabled ? "default" : "secondary"}>
                  {item.enabled ? "Running" : "Paused"}
                </Badge>
              </div>
            </CardHeader>
            <CardContent className="flex flex-wrap justify-end gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => void toggle(item)}
              >
                {item.enabled ? <Pause /> : <Play />}
                {item.enabled ? "Pause" : "Resume"}
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => openEdit(item)}
              >
                <Settings2 /> Edit
              </Button>
            </CardContent>
          </Card>
        ))
      )}

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>
              {editing
                ? "Edit local organization rule"
                : "New local organization rule"}
            </DialogTitle>
            <DialogDescription>
              Files are moved after download. Preview paths before enabling
              historical organization.
            </DialogDescription>
          </DialogHeader>

          <div className="grid gap-5 py-2">
            <div className="grid gap-2">
              <Label>Telegram account</Label>
              <Select
                value={draft.telegramId || undefined}
                disabled={editing}
                onValueChange={(id) => setDraft(emptyDraft(id))}
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

            <div className="grid gap-2">
              <Label>Source chat</Label>
              <RuleChatPicker
                accountId={draft.telegramId}
                value={draft.sourceChatId}
                source="local"
                eligibleOnly={false}
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
              <Label htmlFor="organize-destination">
                Destination folder on the TG File server
              </Label>
              <Input
                id="organize-destination"
                value={draft.rule.destination}
                placeholder="/media/archive"
                onChange={(event) =>
                  setDraft({
                    ...draft,
                    rule: { ...draft.rule, destination: event.target.value },
                  })
                }
              />
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <div className="grid gap-2">
                <Label>Organization policy</Label>
                <Select
                  value={draft.rule.transferPolicy}
                  onValueChange={(transferPolicy: TransferPolicy) =>
                    setDraft({
                      ...draft,
                      rule: { ...draft.rule, transferPolicy },
                    })
                  }
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="DIRECT">Direct</SelectItem>
                    <SelectItem value="GROUP_BY_CHAT">Group by chat</SelectItem>
                    <SelectItem value="GROUP_BY_TYPE">Group by type</SelectItem>
                    <SelectItem value="GROUP_BY_DATE">
                      Group by message date
                    </SelectItem>
                    <SelectItem value="GROUP_BY_AI">Group by AI</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-2">
                <Label>Duplicate policy</Label>
                <Select
                  value={draft.rule.duplicationPolicy}
                  onValueChange={(duplicationPolicy: DuplicationPolicy) =>
                    setDraft({
                      ...draft,
                      rule: { ...draft.rule, duplicationPolicy },
                    })
                  }
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="RENAME">
                      Rename safely (recommended)
                    </SelectItem>
                    <SelectItem value="HASH">Compare hash</SelectItem>
                    <SelectItem value="SKIP">Skip</SelectItem>
                    <SelectItem value="OVERWRITE">Overwrite</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>

            {draft.rule.transferPolicy === "GROUP_BY_DATE" && (
              <div className="grid gap-4 rounded-lg border p-4 sm:grid-cols-2">
                <div className="grid gap-2">
                  <Label>Date folders</Label>
                  <Select
                    value={draft.rule.extra.dateGrouping ?? "YEAR_MONTH"}
                    onValueChange={(dateGrouping) =>
                      setDraft({
                        ...draft,
                        rule: {
                          ...draft.rule,
                          extra: { ...draft.rule.extra, dateGrouping },
                        },
                      })
                    }
                  >
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="YEAR">Year</SelectItem>
                      <SelectItem value="YEAR_MONTH">Year / month</SelectItem>
                      <SelectItem value="YEAR_MONTH_DAY">
                        Year / month / day
                      </SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <div className="grid gap-2">
                  <Label>Time zone</Label>
                  <Input
                    value={draft.rule.extra.timezone ?? "Asia/Shanghai"}
                    onChange={(event) =>
                      setDraft({
                        ...draft,
                        rule: {
                          ...draft.rule,
                          extra: {
                            ...draft.rule.extra,
                            timezone: event.target.value,
                          },
                        },
                      })
                    }
                  />
                </div>
                <div className="flex items-center justify-between gap-3 sm:col-span-2">
                  <div>
                    <Label>Include chat ID folder</Label>
                    <p className="text-xs text-muted-foreground">
                      Example: /destination/-100123/2026/09/file.mp4
                    </p>
                  </div>
                  <Switch
                    checked={draft.rule.extra.includeChatDirectory ?? true}
                    onCheckedChange={(includeChatDirectory) =>
                      setDraft({
                        ...draft,
                        rule: {
                          ...draft.rule,
                          extra: { ...draft.rule.extra, includeChatDirectory },
                        },
                      })
                    }
                  />
                </div>
              </div>
            )}

            {draft.rule.transferPolicy === "GROUP_BY_AI" && (
              <div className="grid gap-2">
                <Label>AI classification prompt</Label>
                <Textarea
                  value={draft.rule.extra.promptTemplate ?? ""}
                  onChange={(event) =>
                    setDraft({
                      ...draft,
                      rule: {
                        ...draft.rule,
                        extra: {
                          ...draft.rule.extra,
                          promptTemplate: event.target.value,
                        },
                      },
                    })
                  }
                />
              </div>
            )}

            <div className="grid gap-3 rounded-lg border p-4">
              <div className="flex items-center justify-between gap-4">
                <div>
                  <Label>Add caption to file name</Label>
                  <p className="text-xs text-muted-foreground">
                    Unsafe filename characters are removed.
                  </p>
                </div>
                <Switch
                  checked={draft.rule.useCaptionName ?? false}
                  onCheckedChange={(useCaptionName) =>
                    setDraft({
                      ...draft,
                      rule: { ...draft.rule, useCaptionName },
                    })
                  }
                />
              </div>
              <div className="flex items-center justify-between gap-4">
                <div>
                  <Label>Organize downloaded history</Label>
                  <p className="text-xs text-muted-foreground">
                    Existing completed files enter the move queue after saving.
                  </p>
                </div>
                <Switch
                  checked={draft.rule.transferHistory}
                  onCheckedChange={(transferHistory) =>
                    setDraft({
                      ...draft,
                      rule: { ...draft.rule, transferHistory },
                    })
                  }
                />
              </div>
              <div className="flex items-center justify-between gap-4">
                <div>
                  <Label>Rule enabled</Label>
                  <p className="text-xs text-muted-foreground">
                    Paused rules stay saved.
                  </p>
                </div>
                <Switch
                  checked={draft.enabled}
                  onCheckedChange={(enabled) => setDraft({ ...draft, enabled })}
                />
              </div>
            </div>

            {preview && (
              <div className="rounded-lg border bg-muted/20 p-3">
                <div className="mb-2 flex items-center gap-2 text-sm font-medium">
                  <CalendarDays className="size-4" /> {preview.total} eligible
                  historical files
                </div>
                <div className="max-h-52 space-y-2 overflow-auto">
                  {preview.items.length === 0 ? (
                    <p className="text-sm text-muted-foreground">
                      No idle completed files to preview.
                    </p>
                  ) : (
                    preview.items.map((item) => (
                      <div
                        key={item.uniqueId}
                        className="rounded border bg-background p-2 text-xs"
                      >
                        <p
                          className="truncate text-muted-foreground"
                          translate="no"
                        >
                          {item.sourcePath}
                        </p>
                        <p className="truncate" translate="no">
                          → {item.destinationPath}
                        </p>
                        {item.destinationExists && (
                          <Badge variant="secondary" className="mt-1">
                            Destination exists
                          </Badge>
                        )}
                      </div>
                    ))
                  )}
                </div>
              </div>
            )}
          </div>

          <DialogFooter className="flex-col gap-2 sm:flex-row">
            <Button
              variant="outline"
              disabled={!validDraft || previewing}
              onClick={() => void previewPaths()}
            >
              {previewing ? <Loader2 className="animate-spin" /> : <Search />}
              Preview paths
            </Button>
            <Button
              disabled={!validDraft || saving}
              onClick={() => void save()}
            >
              {saving ? <Loader2 className="animate-spin" /> : <FolderInput />}
              Save rule
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
