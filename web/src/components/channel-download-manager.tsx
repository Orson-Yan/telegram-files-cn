"use client";

import { useMemo, useState } from "react";
import useSWR from "swr";
import prettyBytes from "pretty-bytes";
import { useDebounce } from "use-debounce";
import { useTelegramAccount } from "@/hooks/use-telegram-account";
import { createDefaultAuto } from "@/lib/automation";
import { POST, request } from "@/lib/api";
import type { AutoDownloadRule, FileType, TelegramChat } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Download,
  Plus,
  RefreshCw,
  Pause,
  Play,
  Copy,
  MoreVertical,
  Search,
  Filter,
  FileText,
  AlertTriangle,
  FolderOpen,
  ChevronDown,
  ChevronUp,
} from "lucide-react";

export interface ChannelTask {
  id: string;
  account_id: string;
  chat_id: string;
  title: string;
  rule_json: string;
  enabled: number;
  paused: number;
  initialized: number;
  history_done: number;
  counts: Record<string, number>;
  bytes: Record<string, number>;
  scanned_count: number;
  last_check: number;
  next_scan_at: number;
  last_error?: string;
  blocked_reason?: string;
  comment_scans?: number;
  comment_errors?: number;
  last_success_at?: number;
}

interface FileFailure {
  id: string;
  file_name?: string;
  status: string;
  attempts: number;
  last_error?: string;
}

// 标准下载类型定义（对齐图 2）
export const telegramFileTypes = [
  { key: "photo", label: "照片", desc: "照片 / 图片" },
  { key: "video", label: "视频", desc: "视频（含文件形式）" },
  { key: "audio", label: "音频", desc: "音乐 / 音频" },
  { key: "file", label: "文件", desc: "文档 / 压缩包等全部文件" },
] as const;

export const rulePresets = [
  { key: "all", label: "所有文件（默认）", types: [] },
  { key: "media", label: "媒体（照片 + 视频）", types: ["photo", "video"] },
  { key: "photo", label: "仅照片", types: ["photo"] },
  { key: "video", label: "仅视频", types: ["video"] },
  { key: "audio", label: "仅音频", types: ["audio"] },
  { key: "file", label: "仅文件", types: ["file"] },
] as const;

const fieldClass = "rounded-md border bg-background px-3 py-2 text-sm";

function localDate(value?: number): string {
  if (!value) return "";
  const date = new Date(value * 1000);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
    .toISOString()
    .slice(0, 10);
}

export function channelTaskState(task: ChannelTask): string {
  if (!task.enabled) return "规则已关闭";
  if (task.paused) return "已暂停派发";
  if (task.blocked_reason) return task.blocked_reason;
  if (task.last_error) return "等待恢复";
  if (!task.initialized) return "准备扫描";
  if (!task.history_done) return "扫描历史中";
  if ((task.comment_scans ?? 0) > 0) return "扫描评论历史中";
  if (
    (task.counts.WAITING ?? 0) +
      (task.counts.RUNNING ?? 0) +
      (task.counts.STARTING ?? 0) +
      (task.counts.RETRY ?? 0) >
    0
  )
    return "历史已扫描，继续下载中";
  if ((task.counts.FAILED ?? 0) > 0) return "存在失败文件，持续监听中";
  if ((task.counts.PAUSED ?? 0) > 0) return "有文件暂停，持续监听中";
  if ((task.comment_errors ?? 0) > 0) return "部分评论不可访问，持续监听中";
  return "历史已完成，持续监听中";
}

export function downloadTemplate(preset: string): AutoDownloadRule {
  const matched = rulePresets.find((p) => p.key === preset);
  const types = matched ? [...matched.types] : [];
  return {
    ...createDefaultAuto().download.rule,
    fileTypes: types as Array<Exclude<FileType, "media">>,
    contentTypes: types,
    downloadHistory: true,
  };
}

export function ChannelDownloadManager() {
  const { data: tasks, error, mutate } = useSWR<ChannelTask[]>(
    "/channel-downloads",
    { refreshInterval: 5_000, refreshWhenHidden: false },
  );
  const { getAccounts, accountId: currentAccount } = useTelegramAccount();
  const accounts = getAccounts();
  const [open, setOpen] = useState(false);
  const [account, setAccount] = useState("");
  const accountId = account || currentAccount || accounts[0]?.id || "";
  const [search, setSearch] = useState("");
  const [debouncedSearch] = useDebounce(search, 300);
  const [limit, setLimit] = useState(100);
  const [selected, setSelected] = useState<string[]>([]);
  const [links, setLinks] = useState("");
  const [rule, setRule] = useState<AutoDownloadRule>(() => downloadTemplate("all"));
  const [destination, setDestination] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [sample, setSample] = useState<
    { name: string; category?: string; type?: string; size?: number; matched: boolean }[]
  >([]);
  const [checked, setChecked] = useState<string[]>([]);
  const [failureSource, setFailureSource] = useState<string | null>(null);
  const [showAdvanced, setShowAdvanced] = useState(false);

  // 任务列表搜索与筛选
  const [taskQuery, setTaskQuery] = useState("");
  const [taskFilter, setTaskFilter] = useState<"all" | "active" | "completed" | "error" | "paused">("all");

  const { data: failures } = useSWR<FileFailure[]>(
    failureSource ? `/channel-downloads/${encodeURIComponent(failureSource)}/files` : null,
  );
  const { data: chats, isLoading, error: chatError, mutate: reloadChats } = useSWR<TelegramChat[]>(
    accountId
      ? `/telegram/${accountId}/chats?scope=all&limit=${limit}&query=${encodeURIComponent(debouncedSearch)}`
      : null,
    { refreshInterval: 30_000, refreshWhenHidden: false },
  );

  const toggle = (values: string[], value: string) =>
    values.includes(value) ? values.filter((item) => item !== value) : [...values, value];

  // 过滤后的任务列表
  const filteredTasks = useMemo(() => {
    if (!tasks) return [];
    return tasks.filter((task) => {
      // 搜索过滤
      if (taskQuery.trim()) {
        const query = taskQuery.toLowerCase().trim();
        const matchTitle = (task.title || "").toLowerCase().includes(query);
        const matchChatId = String(task.chat_id).includes(query);
        const matchAccountId = String(task.account_id).includes(query);
        if (!matchTitle && !matchChatId && !matchAccountId) return false;
      }
      // 状态过滤
      if (taskFilter === "paused") {
        return task.paused === 1 || task.enabled === 0;
      }
      if (taskFilter === "error") {
        return (task.counts.FAILED ?? 0) > 0 || !!task.last_error;
      }
      if (taskFilter === "active") {
        const activeCount =
          (task.counts.WAITING ?? 0) +
          (task.counts.RUNNING ?? 0) +
          (task.counts.STARTING ?? 0) +
          (task.counts.RETRY ?? 0);
        return activeCount > 0 || !task.history_done;
      }
      if (taskFilter === "completed") {
        return (
          task.history_done === 1 &&
          (task.counts.WAITING ?? 0) +
            (task.counts.RUNNING ?? 0) +
            (task.counts.STARTING ?? 0) ===
            0
        );
      }
      return true;
    });
  }, [tasks, taskQuery, taskFilter]);

  async function act(ids: string[], action: string) {
    if (ids.length === 0) return;
    setBusy(action);
    setNotice("");
    try {
      const result = await Promise.allSettled(
        ids.map((id) => POST(`/channel-downloads/${encodeURIComponent(id)}/${action}`, {})),
      );
      const rejected = result.filter((r) => r.status === "rejected");
      if (rejected.length) setNotice(`${rejected.length} 个任务操作失败，请刷新后重试。`);
      await mutate();
    } catch (caught) {
      setNotice(caught instanceof Error ? caught.message : "操作失败");
    } finally {
      setBusy(null);
    }
  }

  async function create() {
    setBusy("create");
    setNotice("");
    try {
      const response = (await POST("/channel-downloads", {
        accountId,
        chatIds: selected,
        links: links
          .split(/\n/)
          .map((line) => line.trim())
          .filter(Boolean),
        rule,
        destination,
      })) as { items: { created: boolean; error?: string; reference?: string }[] };
      const failed = response.items.filter((item) => !item.created);
      await mutate();
      if (failed.length) {
        setNotice(failed.map((item) => `${item.reference ?? "频道"}：${item.error}`).join("；"));
      } else {
        setOpen(false);
        setSelected([]);
        setLinks("");
        setSample([]);
      }
    } catch (caught) {
      setNotice(caught instanceof Error ? caught.message : "创建失败");
    } finally {
      setBusy(null);
    }
  }

  async function preview() {
    setBusy("preview");
    setNotice("");
    try {
      const response = (await POST("/channel-downloads/preview", {
        accountId,
        chatId: selected[0] || links.split(/\n/)[0],
        rule,
      })) as { items: typeof sample };
      setSample(response.items);
    } catch (caught) {
      setNotice(caught instanceof Error ? caught.message : "预览失败");
    } finally {
      setBusy(null);
    }
  }

  async function manifest(id: string) {
    setBusy("manifest");
    try {
      const data: unknown = await request(`/channel-downloads/${encodeURIComponent(id)}/manifest`);
      const url = URL.createObjectURL(
        new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }),
      );
      const link = document.createElement("a");
      link.href = url;
      link.download = "channel-downloads.json";
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (caught) {
      setNotice(caught instanceof Error ? caught.message : "导出失败");
    } finally {
      setBusy(null);
    }
  }

  const handleSelectAllChats = () => {
    if (!chats) return;
    const allIds = chats.map((c) => c.id);
    setSelected((prev) => Array.from(new Set([...prev, ...allIds])).slice(0, 50));
  };

  const handleClearSelectedChats = () => {
    setSelected([]);
  };

  return (
    <Card className="mb-6 shadow-sm">
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-3 border-b bg-muted/10 pb-4">
        <div>
          <CardTitle className="flex items-center gap-2 text-lg">
            <Download className="size-5 text-blue-500" />
            频道下载任务
          </CardTitle>
          <p className="mt-1 text-sm text-muted-foreground">
            自动同步频道全量历史并持续监听新增媒体，断点续传、重启自愈。
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" size="sm" onClick={() => void mutate()}>
            <RefreshCw className="mr-1.5 size-4" />
            刷新
          </Button>
          <Button
            size="sm"
            onClick={() => {
              setNotice("");
              setOpen(true);
            }}
          >
            <Plus className="mr-1.5 size-4" />
            新建频道任务
          </Button>
        </div>
      </CardHeader>

      <CardContent className="pt-4">
        {/* 顶部搜索与筛选栏 */}
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-1 flex-wrap items-center gap-2 sm:max-w-md">
            <div className="relative w-full">
              <Search className="absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
              <Input
                placeholder="搜索频道名称或 ID..."
                value={taskQuery}
                onChange={(e) => setTaskQuery(e.target.value)}
                className="pl-9 text-sm"
              />
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-1.5">
            <Filter className="size-4 text-muted-foreground" />
            {(
              [
                ["all", "全部"],
                ["active", "同步中"],
                ["completed", "已完成"],
                ["error", "有异常"],
                ["paused", "已暂停"],
              ] as const
            ).map(([key, label]) => (
              <Button
                key={key}
                size="sm"
                variant={taskFilter === key ? "secondary" : "ghost"}
                className={`h-8 px-2.5 text-xs font-medium ${
                  taskFilter === key ? "bg-primary/10 text-primary hover:bg-primary/15" : ""
                }`}
                onClick={() => setTaskFilter(key)}
              >
                {label}
              </Button>
            ))}
          </div>
        </div>

        {error ? (
          <p role="alert" className="mb-3 rounded-md bg-destructive/10 p-3 text-sm text-destructive">
            频道任务加载失败，请刷新重试。
          </p>
        ) : null}
        {notice && !open ? (
          <p role="alert" className="mb-3 rounded-md bg-destructive/10 p-3 text-sm text-destructive">
            {notice}
          </p>
        ) : null}

        {/* 批量操作工具栏 */}
        {checked.length > 0 ? (
          <div className="mb-4 flex flex-wrap items-center gap-2 rounded-md border bg-muted/30 p-2.5 text-sm">
            <span className="font-medium text-foreground">已选 {checked.length} 个任务</span>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={!!busy}
                onClick={() => void act(checked, "pause")}
              >
                批量暂停
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={!!busy}
                onClick={() => void act(checked, "resume")}
              >
                批量继续
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={!!busy}
                onClick={() => void act(checked, "retry")}
              >
                重试失败
              </Button>
            </div>
            <Button
              size="sm"
              variant="ghost"
              className="ml-auto text-xs text-muted-foreground"
              onClick={() => setChecked([])}
            >
              取消勾选
            </Button>
          </div>
        ) : null}

        {/* 空列表状态 */}
        {tasks && tasks.length === 0 ? (
          <div className="flex min-h-36 flex-col items-center justify-center rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
            <FolderOpen className="mb-2 size-8 text-muted-foreground/60" />
            <p className="font-medium text-foreground">暂无频道下载任务</p>
            <p className="mt-1 text-xs text-muted-foreground">
              点击上方“新建频道任务”按钮，选择频道即可开始下载全部历史文件并持续监听新增。
            </p>
          </div>
        ) : null}

        {tasks && tasks.length > 0 && filteredTasks.length === 0 ? (
          <p className="py-8 text-center text-sm text-muted-foreground">
            没有符合当前筛选条件的任务。
          </p>
        ) : null}

        {/* 任务卡片网格 */}
        <div className="grid gap-4 xl:grid-cols-2">
          {filteredTasks.map((task) => {
            const c = task.counts;
            const discovered = Object.values(c).reduce((sum, value) => sum + value, 0);
            const completedCount = c.COMPLETED ?? 0;
            const failedCount = (c.FAILED ?? 0) + (c.RETRY ?? 0);
            const runningCount = (c.RUNNING ?? 0) + (c.STARTING ?? 0);
            const progressPercent =
              discovered > 0 ? Math.min(100, Math.round((completedCount / discovered) * 100)) : 0;

            const isPaused = task.paused || !task.enabled;
            const hasError = failedCount > 0 || task.last_error;

            return (
              <article
                key={task.id}
                className="flex flex-col justify-between rounded-lg border bg-card p-4 transition-shadow hover:shadow-sm"
              >
                <div>
                  {/* 卡片头部 */}
                  <div className="flex items-start gap-3">
                    <input
                      type="checkbox"
                      aria-label={`选择任务 ${task.title}`}
                      checked={checked.includes(task.id)}
                      onChange={() => setChecked(toggle(checked, task.id))}
                      className="mt-1 size-4 rounded border-gray-300 accent-primary"
                    />
                    <div className="min-w-0 flex-1">
                      <p className="truncate font-medium text-foreground">{task.title}</p>
                      <p className="mt-0.5 break-all text-xs text-muted-foreground">
                        账号 {task.account_id} · 频道 {task.chat_id}
                      </p>
                    </div>
                    <Badge
                      variant={isPaused ? "outline" : hasError ? "destructive" : "secondary"}
                      className="max-w-44 whitespace-normal text-right text-xs"
                    >
                      {channelTaskState(task)}
                    </Badge>
                  </div>

                  {/* 进度条指示 */}
                  {discovered > 0 ? (
                    <div className="mt-3">
                      <div className="mb-1 flex items-center justify-between text-xs text-muted-foreground">
                        <span>下载进度 ({completedCount}/{discovered})</span>
                        <span className="font-mono font-medium">{progressPercent}%</span>
                      </div>
                      <Progress value={progressPercent} className="h-1.5" />
                    </div>
                  ) : null}

                  {/* 统计指标卡网格 */}
                  <div className="my-3 grid grid-cols-3 gap-2 text-center text-xs sm:grid-cols-6">
                    <div className="rounded border bg-muted/20 p-1.5">
                      <p className="text-muted-foreground">已发现</p>
                      <p className="mt-0.5 font-mono font-semibold">{discovered}</p>
                    </div>
                    <div className="rounded border bg-muted/20 p-1.5">
                      <p className="text-muted-foreground">排队等待</p>
                      <p className="mt-0.5 font-mono font-semibold">{c.WAITING ?? 0}</p>
                    </div>
                    <div className="rounded border bg-muted/20 p-1.5">
                      <p className="text-muted-foreground">下载中</p>
                      <p className="mt-0.5 font-mono font-semibold text-blue-600 dark:text-blue-400">
                        {runningCount}
                      </p>
                    </div>
                    <div className="rounded border bg-muted/20 p-1.5">
                      <p className="text-muted-foreground">已完成</p>
                      <p className="mt-0.5 font-mono font-semibold text-emerald-600 dark:text-emerald-400">
                        {completedCount}
                      </p>
                    </div>
                    <div
                      className={`rounded border p-1.5 ${
                        failedCount > 0 ? "border-destructive/30 bg-destructive/10" : "bg-muted/20"
                      }`}
                    >
                      <p className={failedCount > 0 ? "text-destructive font-medium" : "text-muted-foreground"}>
                        失败/待重试
                      </p>
                      <p
                        className={`mt-0.5 font-mono font-semibold ${
                          failedCount > 0 ? "text-destructive" : ""
                        }`}
                      >
                        {failedCount}
                      </p>
                    </div>
                    <div className="rounded border bg-muted/20 p-1.5">
                      <p className="text-muted-foreground">完成体积</p>
                      <p className="mt-0.5 font-mono font-semibold">
                        {prettyBytes(task.bytes.COMPLETED ?? 0)}
                      </p>
                    </div>
                  </div>

                  {/* 状态与扫描详情 */}
                  <div className="space-y-1 text-xs text-muted-foreground">
                    <p>
                      已扫描 {task.scanned_count} 条消息 ·{" "}
                      {task.last_check
                        ? `最近核对 ${new Date(task.last_check).toLocaleTimeString()}`
                        : "等待首次检查"}
                      {task.last_success_at
                        ? ` · 最近成功 ${new Date(task.last_success_at).toLocaleTimeString()}`
                        : ""}
                    </p>
                    {task.comment_errors ? (
                      <p className="text-amber-600 dark:text-amber-400">
                        存在 {task.comment_errors} 个受限或不可访问的评论区
                      </p>
                    ) : null}
                    {task.last_error ? (
                      <p className="rounded bg-destructive/10 p-1.5 text-xs text-destructive">
                        {task.last_error}
                        {task.next_scan_at > Date.now()
                          ? ` · ${new Date(task.next_scan_at).toLocaleTimeString()} 后重试`
                          : ""}
                      </p>
                    ) : null}
                  </div>
                </div>

                {/* 底部按钮栏：主操作高亮，次要操作收进下拉菜单 */}
                <div className="mt-3 flex items-center justify-between border-t pt-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Button
                      size="sm"
                      variant={isPaused ? "default" : "outline"}
                      className="h-8 gap-1.5 text-xs"
                      disabled={!!busy}
                      onClick={() => void act([task.id], isPaused ? "resume" : "pause")}
                    >
                      {isPaused ? <Play className="size-3.5" /> : <Pause className="size-3.5" />}
                      {isPaused ? "继续" : "暂停派发"}
                    </Button>
                    {failedCount > 0 ? (
                      <Button
                        size="sm"
                        variant="destructive"
                        className="h-8 text-xs"
                        disabled={!!busy}
                        onClick={() => void act([task.id], "retry")}
                      >
                        重试失败 ({failedCount})
                      </Button>
                    ) : null}
                    <Button
                      size="sm"
                      variant="ghost"
                      className="h-8 text-xs"
                      disabled={!!busy}
                      onClick={() => void act([task.id], "rescan")}
                    >
                      补扫历史
                    </Button>
                  </div>

                  {/* 更多操作下拉菜单 */}
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button variant="ghost" size="icon" className="size-8" aria-label="更多操作">
                        <MoreVertical className="size-4" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end" className="w-36">
                      <DropdownMenuItem
                        onClick={() =>
                          setFailureSource(failureSource === task.id ? null : task.id)
                        }
                      >
                        <AlertTriangle className="mr-2 size-3.5" />
                        失败明细
                      </DropdownMenuItem>
                      <DropdownMenuItem
                        disabled={!!busy}
                        onClick={() => void manifest(task.id)}
                      >
                        <FileText className="mr-2 size-3.5" />
                        导出清单
                      </DropdownMenuItem>
                      <DropdownMenuSeparator />
                      <DropdownMenuItem
                        onClick={() => {
                          setAccount(task.account_id);
                          setRule({
                            ...downloadTemplate("all"),
                            ...JSON.parse(task.rule_json),
                          });
                          setSelected([task.chat_id]);
                          setLinks("");
                          setDestination("");
                          setSample([]);
                          setNotice("");
                          setOpen(true);
                        }}
                      >
                        编辑规则
                      </DropdownMenuItem>
                      <DropdownMenuItem
                        onClick={() => {
                          setAccount(task.account_id);
                          setRule({
                            ...downloadTemplate("all"),
                            ...JSON.parse(task.rule_json),
                          });
                          setSelected([]);
                          setLinks("");
                          setDestination("");
                          setSample([]);
                          setNotice("");
                          setOpen(true);
                        }}
                      >
                        <Copy className="mr-2 size-3.5" />
                        复制规则
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
                </div>

                {/* 失败明细展开 */}
                {failureSource === task.id ? (
                  <div className="mt-3 space-y-1.5 rounded border border-destructive/20 bg-destructive/5 p-3 text-xs">
                    <div className="flex items-center justify-between font-medium text-destructive">
                      <span>失败或等待重试的文件明细</span>
                      <Button
                        size="sm"
                        variant="ghost"
                        className="h-6 text-xs"
                        onClick={() => setFailureSource(null)}
                      >
                        收起
                      </Button>
                    </div>
                    {failures?.length === 0 ? (
                      <p className="text-muted-foreground">没有处于失败状态的文件。</p>
                    ) : (
                      failures?.map((file) => (
                        <p key={file.id} className="break-words text-muted-foreground">
                          <span className="font-medium text-foreground">
                            {file.file_name || file.id}
                          </span>{" "}
                          · 尝试 {file.attempts} 次 · {file.last_error}
                        </p>
                      ))
                    )}
                  </div>
                ) : null}
              </article>
            );
          })}
        </div>
      </CardContent>

      {/* 创建 / 编辑频道下载任务弹窗 */}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle>新建频道下载任务</DialogTitle>
            <DialogDescription>
              选择 Telegram 账号和目标频道，配置筛选规则。同一账号和频道复用已有任务，修改规则后自动按新条件补扫历史。
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4">
            {/* 1. 账号与规则模板 */}
            <div className="grid gap-4 md:grid-cols-2">
              <label className="grid gap-1.5 text-sm font-medium">
                Telegram 账号
                <select
                  aria-label="下载账号"
                  className={fieldClass}
                  value={accountId}
                  onChange={(event) => {
                    setAccount(event.target.value);
                    setSelected([]);
                    setLimit(100);
                  }}
                >
                  <option value="" disabled>
                    请选择账号
                  </option>
                  {accounts.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.name || item.id}（{item.id}）
                    </option>
                  ))}
                </select>
              </label>

              <label className="grid gap-1.5 text-sm font-medium">
                规则模板
                <select
                  aria-label="规则模板"
                  className={fieldClass}
                  onChange={(event) => setRule(downloadTemplate(event.target.value))}
                  defaultValue="all"
                >
                  {rulePresets.map((preset) => (
                    <option key={preset.key} value={preset.key}>
                      {preset.label}
                    </option>
                  ))}
                </select>
              </label>
            </div>

            {/* 2. 目标频道选择 */}
            <div className="rounded-lg border p-3">
              <div className="mb-2 flex items-center justify-between gap-2">
                <span className="text-sm font-medium">选择目标频道</span>
                <div className="flex gap-2">
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="h-7 text-xs"
                    onClick={handleSelectAllChats}
                  >
                    全选当前结果
                  </Button>
                  {selected.length > 0 ? (
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      className="h-7 text-xs text-muted-foreground hover:text-foreground"
                      onClick={handleClearSelectedChats}
                    >
                      清空已选
                    </Button>
                  ) : null}
                </div>
              </div>

              <div className="mb-2 flex gap-2">
                <Input
                  aria-label="搜索下载频道"
                  placeholder="搜索频道名称或 ID（含归档）"
                  value={search}
                  onChange={(event) => {
                    setSearch(event.target.value);
                    setLimit(100);
                  }}
                />
                <Button
                  variant="outline"
                  onClick={() =>
                    void reloadChats(
                      request<TelegramChat[]>(
                        `/telegram/${accountId}/chats?scope=all&limit=${limit}&query=${encodeURIComponent(
                          debouncedSearch,
                        )}&refresh=true`,
                      ),
                      { revalidate: false },
                    ).catch(() => undefined)
                  }
                  aria-label="刷新下载频道"
                >
                  <RefreshCw className="size-4" />
                </Button>
              </div>

              {chatError ? (
                <p role="alert" className="text-sm text-destructive">
                  频道加载失败，请重试。
                </p>
              ) : null}

              <div className="grid max-h-56 gap-1 overflow-y-auto rounded border bg-muted/10 p-2 md:grid-cols-2">
                {isLoading ? (
                  <p className="p-3 text-sm text-muted-foreground">正在加载频道列表…</p>
                ) : chats?.length === 0 ? (
                  <p className="p-3 text-sm text-muted-foreground">未找到匹配频道</p>
                ) : (
                  chats?.map((chat) => (
                    <label
                      key={chat.id}
                      className={`flex min-w-0 cursor-pointer items-center gap-2 rounded p-2 text-sm transition-colors ${
                        selected.includes(chat.id)
                          ? "bg-primary/10 text-primary font-medium"
                          : "hover:bg-muted"
                      }`}
                    >
                      <input
                        type="checkbox"
                        aria-label={chat.name || chat.id}
                        checked={selected.includes(chat.id)}
                        onChange={() => setSelected(toggle(selected, chat.id))}
                        className="size-4 rounded border-gray-300 accent-primary"
                      />
                      <span className="truncate">{chat.name || chat.id}</span>
                      <span className="ml-auto font-mono text-[10px] text-muted-foreground">
                        {chat.id}
                      </span>
                    </label>
                  ))
                )}
              </div>

              {chats?.length === limit && limit < 5000 ? (
                <Button
                  variant="ghost"
                  size="sm"
                  className="mt-2 text-xs"
                  onClick={() => setLimit(limit + 100)}
                >
                  加载更多频道...
                </Button>
              ) : null}
              <p className="mt-2 text-xs text-muted-foreground">
                已选择 <span className="font-semibold text-foreground">{selected.length}</span> 个频道，每次最多创建 50 个任务。
              </p>
            </div>

            {/* 3. 批量链接输入 */}
            <label className="grid gap-1 text-sm font-medium">
              或手动批量粘贴频道 / 消息链接（每行一个）
              <textarea
                className={`${fieldClass} min-h-16 font-mono text-xs`}
                value={links}
                onChange={(event) => setLinks(event.target.value)}
                placeholder="https://t.me/channel_name&#10;https://t.me/c/123456789/100"
              />
            </label>

            {/* 4. 历史同步策略 */}
            <div className="rounded-lg border p-3 text-sm">
              <label className="flex cursor-pointer items-center gap-2 font-medium">
                <input
                  type="checkbox"
                  checked={rule.downloadHistory}
                  onChange={(event) =>
                    setRule({ ...rule, downloadHistory: event.target.checked })
                  }
                  className="size-4 rounded border-gray-300 accent-primary"
                />
                下载全部历史，并持续下载新增文件
              </label>
              <p className="mt-1 pl-6 text-xs text-muted-foreground">
                关闭后仅从创建任务时开始监听接收新增文件。
              </p>
            </div>

            {/* 5. 下载类型（对齐图 2 分类） */}
            <fieldset className="rounded-lg border p-3">
              <div className="mb-2 flex items-center justify-between">
                <legend className="px-1 text-sm font-semibold">
                  下载分类（留空默认下载全部）
                </legend>
                <div className="flex gap-2 text-xs">
                  <button
                    type="button"
                    className="text-primary hover:underline"
                    onClick={() =>
                      setRule({
                        ...rule,
                        fileTypes: [],
                        contentTypes: [],
                      })
                    }
                  >
                    所有文件
                  </button>
                  <span className="text-muted-foreground">|</span>
                  <button
                    type="button"
                    className="text-primary hover:underline"
                    onClick={() =>
                      setRule({
                        ...rule,
                        fileTypes: ["photo", "video"] as typeof rule.fileTypes,
                        contentTypes: ["photo", "video"],
                      })
                    }
                  >
                    仅媒体
                  </button>
                  <span className="text-muted-foreground">|</span>
                  <button
                    type="button"
                    className="text-muted-foreground hover:underline"
                    onClick={() => setRule({ ...rule, fileTypes: [], contentTypes: [] })}
                  >
                    清空
                  </button>
                </div>
              </div>

              <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                {telegramFileTypes.map((item) => {
                  const isChecked = (rule.fileTypes?.includes(
                    item.key as (typeof rule.fileTypes)[number],
                  ) || rule.contentTypes?.includes(item.key)) ?? false;
                  return (
                    <label
                      key={item.key}
                      className={`flex cursor-pointer items-center gap-2 rounded-md border p-2.5 text-sm transition-all ${
                        isChecked
                          ? "border-primary/50 bg-primary/10 text-primary font-medium"
                          : "border-border bg-card hover:bg-muted"
                      }`}
                    >
                      <input
                        type="checkbox"
                        checked={isChecked}
                        aria-label={item.label}
                        onChange={() => {
                          const nextTypes = toggle(
                            rule.fileTypes?.filter((type) => String(type) !== "all") ?? [],
                            item.key,
                          ) as typeof rule.fileTypes;
                          setRule({
                            ...rule,
                            fileTypes: nextTypes,
                            contentTypes: nextTypes,
                          });
                        }}
                        className="size-4 rounded border-gray-300 accent-primary"
                      />
                      <div className="min-w-0">
                        <p className="text-sm font-medium">{item.label}</p>
                        <p className="truncate text-xs text-muted-foreground">{item.desc}</p>
                      </div>
                    </label>
                  );
                })}
              </div>
              <p className="mt-2 text-xs text-muted-foreground">
                标准分类：照片、视频、音频、文件。可一键切换“所有文件”或“仅媒体”，留空默认下载全部。
              </p>
            </fieldset>

            {/* 6. 保存目录 */}
            <label className="grid gap-1.5 text-sm font-medium">
              保存目录（可选，留空沿用全局默认下载位置）
              <Input
                placeholder="/nas/media/telegram"
                value={destination}
                onChange={(event) => setDestination(event.target.value)}
              />
              {destination ? (
                <span className="text-xs text-muted-foreground">
                  下载后将按频道自动整理到 {destination}/频道名称 目录，同名文件自动避让重命名。
                </span>
              ) : null}
            </label>

            {/* 7. 高级筛选折叠 */}
            <div className="rounded-lg border p-3">
              <button
                type="button"
                className="flex w-full items-center justify-between text-left text-sm font-medium"
                onClick={() => setShowAdvanced(!showAdvanced)}
              >
                <span>高级筛选规则（扩展名、体积、日期、表达式）</span>
                {showAdvanced ? (
                  <ChevronUp className="size-4 text-muted-foreground" />
                ) : (
                  <ChevronDown className="size-4 text-muted-foreground" />
                )}
              </button>

              {showAdvanced ? (
                <div className="mt-3 space-y-3 border-t pt-3">
                  <div className="grid gap-3 md:grid-cols-2">
                    <label className="grid gap-1 text-sm">
                      关键词（匹配文件名或说明文字）
                      <Input
                        value={rule.query}
                        placeholder="留空不限制"
                        onChange={(event) => setRule({ ...rule, query: event.target.value })}
                      />
                    </label>
                    <label className="grid gap-1 text-sm">
                      允许扩展名（逗号分隔）
                      <Input
                        placeholder="mp4, mkv, pdf"
                        value={rule.allowedExtensions ?? ""}
                        onChange={(event) =>
                          setRule({ ...rule, allowedExtensions: event.target.value })
                        }
                      />
                    </label>
                    <label className="grid gap-1 text-sm">
                      排除扩展名（优先级最高）
                      <Input
                        placeholder="exe, apk, tmp"
                        value={rule.deniedExtensions ?? ""}
                        onChange={(event) =>
                          setRule({ ...rule, deniedExtensions: event.target.value })
                        }
                      />
                    </label>
                    <div className="grid grid-cols-2 gap-2">
                      {(
                        [
                          ["minSize", "最小体积 (MB)"],
                          ["maxSize", "最大体积 (MB)"],
                        ] as const
                      ).map(([key, label]) => (
                        <label key={key} className="grid gap-1 text-sm">
                          {label}
                          <Input
                            type="number"
                            min="0"
                            placeholder="0"
                            value={rule[key] ? rule[key]! / 1048576 : ""}
                            onChange={(event) =>
                              setRule({
                                ...rule,
                                [key]: Math.max(0, Number(event.target.value)) * 1048576,
                              })
                            }
                          />
                        </label>
                      ))}
                    </div>
                    <div className="grid grid-cols-2 gap-2">
                      {(
                        [
                          ["fromDate", "起始日期"],
                          ["toDate", "结束日期"],
                        ] as const
                      ).map(([key, label]) => (
                        <label key={key} className="grid gap-1 text-sm">
                          {label}
                          <Input
                            type="date"
                            value={localDate(rule[key])}
                            onChange={(event) =>
                              setRule({
                                ...rule,
                                [key]: event.target.value
                                  ? Math.floor(
                                      new Date(
                                        `${event.target.value}T${
                                          key === "toDate" ? "23:59:59" : "00:00:00"
                                        }`,
                                      ).getTime() / 1000,
                                    )
                                  : 0,
                              })
                            }
                          />
                        </label>
                      ))}
                    </div>
                  </div>

                  <label className="grid gap-1 text-sm">
                    筛选表达式 (Filter Expression)
                    <Input
                      placeholder="e.g. file_size < 100000000"
                      value={rule.filterExpr}
                      onChange={(event) => setRule({ ...rule, filterExpr: event.target.value })}
                    />
                  </label>

                  <label className="flex cursor-pointer items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={rule.downloadCommentFiles}
                      onChange={(event) =>
                        setRule({ ...rule, downloadCommentFiles: event.target.checked })
                      }
                      className="size-4 rounded border-gray-300 accent-primary"
                    />
                    同时下载讨论区评论中的文件
                  </label>
                </div>
              ) : null}
            </div>

            {/* 抽样匹配预览 */}
            {sample.length > 0 ? (
              <div className="max-h-48 overflow-auto rounded-lg border bg-muted/20 p-3 text-xs">
                <p className="mb-2 font-medium text-foreground">
                  抽样匹配结果（最近 30 条消息）
                </p>
                <div className="space-y-1.5">
                  {sample.map((item, index) => (
                    <div
                      key={index}
                      className={`flex items-center justify-between rounded p-1.5 ${
                        item.matched ? "bg-emerald-500/10 text-emerald-800 dark:text-emerald-300" : "bg-muted text-muted-foreground"
                      }`}
                    >
                      <div className="flex min-w-0 items-center gap-2">
                        <span className="font-bold">{item.matched ? "✓ 符合规则" : "× 不符合当前筛选"}</span>
                        <span className="truncate">{item.name || "未命名文件"}</span>
                      </div>
                      <div className="flex items-center gap-2 font-mono text-[11px]">
                        {item.type ? <span>[{item.type}]</span> : null}
                        {item.size ? <span>{prettyBytes(item.size)}</span> : null}
                      </div>
                    </div>
                  ))}
                </div>
                <p className="mt-2 text-muted-foreground">
                  注：预览仅抽样频道最近部分消息，实际全量历史数量将在任务创建后扫描统计。
                </p>
              </div>
            ) : null}

            {notice ? (
              <p role="alert" className="break-words rounded bg-destructive/10 p-2 text-sm text-destructive">
                {notice}
              </p>
            ) : null}

            {/* 弹窗底部操作按钮 */}
            <div className="flex justify-end gap-2 border-t pt-3">
              <Button
                variant="outline"
                disabled={!!busy || !accountId || (!selected.length && !links.trim())}
                onClick={() => void preview()}
              >
                {busy === "preview" ? "正在预览…" : "预览匹配样本"}
              </Button>
              <Button
                disabled={
                  !!busy ||
                  !accountId ||
                  (!selected.length && !links.trim()) ||
                  selected.length + links.split(/\n/).filter((line) => line.trim()).length > 50
                }
                onClick={() => void create()}
              >
                {busy === "create" ? "正在创建…" : "创建并开始下载"}
              </Button>
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </Card>
  );
}
