"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import useSWR from "swr";
import prettyBytes from "pretty-bytes";
import {
  Activity,
  Download,
  ListChecks,
  Pause,
  Play,
  RefreshCw,
  TriangleAlert,
  X,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import { Button } from "@/components/ui/button";
import { useWebsocket } from "@/hooks/use-websocket";
import { useSettings } from "@/hooks/use-settings";
import { type TDFile, type TelegramFile } from "@/lib/types";
import { POST } from "@/lib/api";
import { toast } from "@/hooks/use-toast";
import {
  type DownloadOverviewStatistics,
  normalizeDownloadOverview,
} from "@/lib/download-activity";
import {
  type WebSocketMessage,
  WebSocketMessageType,
} from "@/lib/websocket-types";
import Link from "next/link";

const DOWNLOADING_FILES_URL =
  "/files?type=all&downloadStatus=downloading&limit=100";
const STALLED_AFTER_MILLIS = 30_000;

interface DownloadingFilesResponse {
  files: TelegramFile[];
  count: number;
}

interface TrackedDownload extends TelegramFile {
  speed: number;
  lastProgressAt: number;
}

interface FileSample {
  downloadedSize: number;
  timestamp: number;
  speed: number;
  lastProgressAt: number;
}

function formatDuration(seconds: number) {
  if (!Number.isFinite(seconds) || seconds <= 0) return "--";
  if (seconds < 60) return `${Math.ceil(seconds)}秒`;
  if (seconds < 3_600) return `${Math.ceil(seconds / 60)}分钟`;
  if (seconds < 86_400) return `${(seconds / 3_600).toFixed(1)}小时`;
  return `${(seconds / 86_400).toFixed(1)}天`;
}

function percentage(downloadedSize: number, totalSize: number) {
  if (totalSize <= 0) return 0;
  return Math.min(100, Math.max(0, (downloadedSize / totalSize) * 100));
}

export function DownloadMonitor({
  statistics,
}: {
  statistics: DownloadOverviewStatistics;
}) {
  const { lastJsonMessage, downloadActivity } = useWebsocket();
  const { settings } = useSettings();
  const overview = normalizeDownloadOverview(statistics);
  const { data, error, mutate } = useSWR<DownloadingFilesResponse>(
    DOWNLOADING_FILES_URL,
    { refreshInterval: 15_000, refreshWhenHidden: false },
  );
  const [downloads, setDownloads] = useState<TrackedDownload[]>([]);
  const [now, setNow] = useState(() => Date.now());
  const [operatingId, setOperatingId] = useState<string | null>(null);
  const downloadsRef = useRef<TrackedDownload[]>([]);
  const samples = useRef(new Map<string, FileSample>());

  const handleTogglePause = async (file: TrackedDownload) => {
    try {
      setOperatingId(file.uniqueId);
      const isCurrentlyDownloading = file.downloadStatus === "downloading";
      await POST(
        file.source === "SEED"
          ? "/files/toggle-pause-download-multiple"
          : `/${file.telegramId}/file/toggle-pause-download`,
        file.source === "SEED"
          ? {
              files: [
                {
                  telegramId: 0,
                  uniqueId: file.uniqueId,
                  id: file.id,
                  chatId: file.chatId,
                },
              ],
              isPaused: isCurrentlyDownloading,
            }
          : { fileId: file.id, isPaused: isCurrentlyDownloading },
      );
      toast({
        title: isCurrentlyDownloading ? "已暂停下载" : "已恢复下载",
        description: file.fileName || `${file.type} #${file.id}`,
      });
      void mutate();
    } catch (err: any) {
      toast({
        variant: "error",
        title: "操作失败",
        description: err?.message || "更新下载状态失败",
      });
    } finally {
      setOperatingId(null);
    }
  };

  const handleCancel = async (file: TrackedDownload) => {
    try {
      setOperatingId(file.uniqueId);
      await POST(
        file.source === "SEED"
          ? "/files/cancel-download-multiple"
          : `/${file.telegramId}/file/cancel-download`,
        file.source === "SEED"
          ? {
              files: [
                {
                  telegramId: 0,
                  uniqueId: file.uniqueId,
                  id: file.id,
                  chatId: file.chatId,
                },
              ],
            }
          : { fileId: file.id },
      );
      toast({
        title: "已取消下载",
        description: file.fileName || `${file.type} #${file.id}`,
      });
      setDownloads((prev) => prev.filter((d) => d.uniqueId !== file.uniqueId));
      void mutate();
    } catch (err: any) {
      toast({
        variant: "error",
        title: "取消失败",
        description: err?.message || "未能取消下载",
      });
    } finally {
      setOperatingId(null);
    }
  };

  const handleRetry = async (file: TrackedDownload) => {
    try {
      setOperatingId(file.uniqueId);
      await POST(`/${file.telegramId}/file/cancel-download`, { fileId: file.id });
      await new Promise((r) => setTimeout(r, 500));
      await POST(`/${file.telegramId}/file/start-download`, {
        chatId: file.chatId,
        messageId: file.messageId,
        fileId: file.id,
      });
      toast({
        title: "已重新尝试下载停滞文件",
        description: file.fileName || `${file.type} #${file.id}`,
      });
      void mutate();
    } catch (err: any) {
      toast({
        variant: "error",
        title: "重试失败",
        description: err?.message || "重启下载失败",
      });
    } finally {
      setOperatingId(null);
    }
  };

  const handlePauseAll = async () => {
    if (downloads.length === 0) return;
    try {
      setOperatingId("global");
      await POST("/files/toggle-pause-download-multiple", {
        files: downloads.map((f) => ({
          telegramId: f.telegramId,
          uniqueId: f.uniqueId,
          id: f.id,
          chatId: f.chatId,
        })),
        isPaused: true,
      });
      toast({
        title: "已暂停所有活跃下载",
      });
      void mutate();
    } catch (err: any) {
      toast({
        variant: "error",
        title: "批量暂停失败",
        description: err?.message || "无法暂停全部下载",
      });
    } finally {
      setOperatingId(null);
    }
  };

  const handleResumeAll = async () => {
    if (downloads.length === 0) return;
    try {
      setOperatingId("global");
      await POST("/files/toggle-pause-download-multiple", {
        files: downloads.map((f) => ({
          telegramId: f.telegramId,
          uniqueId: f.uniqueId,
          id: f.id,
          chatId: f.chatId,
        })),
        isPaused: false,
      });
      toast({
        title: "已恢复所有下载",
      });
      void mutate();
    } catch (err: any) {
      toast({
        variant: "error",
        title: "批量恢复失败",
        description: err?.message || "无法恢复全部下载",
      });
    } finally {
      setOperatingId(null);
    }
  };

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 5_000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!data) return;
    const current = new Map(
      downloadsRef.current.map((file) => [file.uniqueId, file]),
    );
    const timestamp = Date.now();
    const next = data.files.map((file) => {
      const existing = current.get(file.uniqueId);
      const downloadedSize = Math.max(
        file.downloadedSize,
        existing?.downloadedSize ?? 0,
      );
      if (!samples.current.has(file.uniqueId)) {
        samples.current.set(file.uniqueId, {
          downloadedSize,
          timestamp,
          speed: existing?.speed ?? 0,
          lastProgressAt: existing?.lastProgressAt ?? timestamp,
        });
      }
      return {
        ...file,
        downloadedSize,
        speed: existing?.speed ?? 0,
        lastProgressAt: existing?.lastProgressAt ?? timestamp,
      };
    });
    downloadsRef.current = next;
    setDownloads(next);
  }, [data]);

  useEffect(() => {
    if (!lastJsonMessage) return;
    const payload = lastJsonMessage as WebSocketMessage;

    if (payload.type === WebSocketMessageType.FILE_STATUS) {
      const status = payload.data as {
        uniqueId?: string;
        downloadStatus?: string;
      };
      if (status.uniqueId && status.downloadStatus !== "downloading") {
        const next = downloadsRef.current.filter(
          (file) => file.uniqueId !== status.uniqueId,
        );
        downloadsRef.current = next;
        samples.current.delete(status.uniqueId);
        setDownloads(next);
      }
      return;
    }

    if (payload.type !== WebSocketMessageType.FILE_UPDATE) return;
    const file = (payload.data as { file?: TDFile })?.file;
    const uniqueId = file?.remote?.uniqueId;
    if (!file || !file.local || !uniqueId) return;

    if (file.local.isDownloadingCompleted) {
      const next = downloadsRef.current.filter(
        (download) => download.uniqueId !== uniqueId,
      );
      downloadsRef.current = next;
      samples.current.delete(uniqueId);
      setDownloads(next);
      return;
    }

    const existing = downloadsRef.current.find(
      (download) => download.uniqueId === uniqueId,
    );
    if (!existing) {
      return;
    }

    const timestamp = payload.timestamp || Date.now();
    const downloadedSize = Math.max(0, file.local.downloadedSize);
    const previous = samples.current.get(uniqueId);
    const elapsedSeconds = previous
      ? (timestamp - previous.timestamp) / 1_000
      : 0;
    const delta = previous ? downloadedSize - previous.downloadedSize : 0;
    const progressed = elapsedSeconds > 0 && delta > 0;
    const sample: FileSample = {
      downloadedSize,
      timestamp,
      speed: progressed ? delta / elapsedSeconds : (previous?.speed ?? 0),
      lastProgressAt: progressed
        ? timestamp
        : (previous?.lastProgressAt ?? timestamp),
    };
    samples.current.set(uniqueId, sample);

    const next = downloadsRef.current.map((download) =>
      download.uniqueId === uniqueId
        ? {
            ...download,
            id: file.id,
            size: Math.max(download.size, file.size, file.expectedSize),
            downloadedSize,
            speed: timestamp - sample.lastProgressAt > 5_000 ? 0 : sample.speed,
            lastProgressAt: sample.lastProgressAt,
          }
        : download,
    );
    downloadsRef.current = next;
    setDownloads(next);
  }, [lastJsonMessage]);

  const estimatedSeconds = useMemo(() => {
    if (downloadActivity.speed <= 0) return 0;
    const activeRemaining = Math.max(
      0,
      downloadActivity.totalSize - downloadActivity.downloadedSize,
    );
    return (overview.queuedSize + activeRemaining) / downloadActivity.speed;
  }, [downloadActivity, overview.queuedSize]);

  const speedOptions = { bits: settings?.speedUnits === "bits" };
  const aggregateStalled =
    overview.downloading > 0 &&
    downloadActivity.speed === 0 &&
    downloadActivity.lastProgressAt > 0 &&
    now - downloadActivity.lastProgressAt > STALLED_AFTER_MILLIS;

  return (
    <Card className="mb-6 shadow-sm">
      <CardHeader className="gap-3 border-b bg-muted/10 pb-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <CardTitle className="flex items-center gap-2 text-lg">
              <Activity className="size-5 text-blue-500" />
              活跃下载与传输队列
            </CardTitle>
            <p className="mt-1 text-sm text-muted-foreground">
              实时下载速率、网络流量、队列槽位及单文件进度监控。
            </p>
          </div>
          <div className="flex items-center gap-2">
            {downloads.length > 0 && (
              <>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={operatingId !== null}
                  onClick={handlePauseAll}
                  className="gap-1 text-xs"
                >
                  <Pause className="size-3.5" />
                  全部暂停
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={operatingId !== null}
                  onClick={handleResumeAll}
                  className="gap-1 text-xs"
                >
                  <Play className="size-3.5" />
                  全部继续
                </Button>
              </>
            )}
            <Button asChild variant="outline" size="sm">
              <Link href="/files">查看全部文件</Link>
            </Button>
          </div>
        </div>

        {/* 顶部指标看板 */}
        <div className="grid grid-cols-2 gap-2 md:grid-cols-5">
          <Metric
            label="并发下载槽位"
            value={`${overview.downloading} / ${overview.downloadLimit}`}
          />
          <Metric
            label="实时下载速度"
            value={`${prettyBytes(downloadActivity.speed, speedOptions)}/s`}
            warning={aggregateStalled}
          />
          <Metric
            label="当前活跃传输"
            value={`${prettyBytes(downloadActivity.downloadedSize)} / ${prettyBytes(
              downloadActivity.totalSize || overview.activeSize,
            )}`}
          />
          <Metric
            label="本次会话流量"
            value={prettyBytes(downloadActivity.sessionDownloadedBytes)}
          />
          <Metric
            label="预计完成时间"
            value={formatDuration(estimatedSeconds)}
          />
        </div>

        {/* 排队与异常提示 */}
        <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
          <ListChecks className="size-4" />
          <span>{overview.queued} 个文件排队中</span>
          <span>·</span>
          <span>共 {prettyBytes(overview.queuedSize)}</span>
          {aggregateStalled && (
            <Badge variant="destructive" className="gap-1 text-xs">
              <TriangleAlert className="size-3" />
              下载可能已停滞
            </Badge>
          )}
        </div>
      </CardHeader>

      <CardContent className="pt-4">
        {error ? (
          <div className="rounded-md border border-destructive/40 bg-destructive/10 p-4 text-sm text-destructive">
            加载活跃下载列表失败，请检查服务连接。
          </div>
        ) : downloads.length === 0 ? (
          <div className="flex min-h-36 flex-col items-center justify-center rounded-lg border border-dashed text-sm text-muted-foreground">
            <Download className="mb-2 size-8 text-muted-foreground/60" />
            <p className="font-medium text-foreground">当前没有进行中的下载</p>
            <p className="mt-1 text-xs text-muted-foreground">
              排队等待中的文件将在槽位空闲时自动派发开始。
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {downloads.map((file) => {
              const progress = percentage(file.downloadedSize, file.size);
              const stalled = now - file.lastProgressAt > STALLED_AFTER_MILLIS;
              return (
                <div
                  key={file.uniqueId}
                  className="rounded-lg border bg-card p-3 shadow-xs transition-shadow hover:shadow-sm"
                >
                  <div className="mb-2 flex flex-wrap items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="truncate font-medium text-sm text-foreground">
                        {file.fileName || `${file.type} #${file.id}`}
                      </p>
                      <p className="mt-0.5 text-xs text-muted-foreground">
                        {prettyBytes(file.downloadedSize)} /{" "}
                        {prettyBytes(file.size)}
                      </p>
                    </div>
                    <div className="flex items-center gap-2 text-xs">
                      {stalled && (
                        <Badge variant="destructive" className="text-[10px]">
                          可能停滞
                        </Badge>
                      )}
                      <Badge variant="secondary" className="font-mono text-xs">
                        <Download className="mr-1 size-3" />
                        {prettyBytes(stalled ? 0 : file.speed, speedOptions)}/s
                      </Badge>
                      <Badge variant="outline" className="font-mono text-xs">
                        {progress.toFixed(1)}%
                      </Badge>
                      <div className="flex items-center gap-1 border-l pl-2">
                        <Button
                          size="icon"
                          variant="ghost"
                          className="size-7 text-muted-foreground hover:text-foreground"
                          title={file.downloadStatus === "paused" ? "继续下载" : "暂停下载"}
                          disabled={operatingId === file.uniqueId}
                          onClick={() => handleTogglePause(file)}
                        >
                          {file.downloadStatus === "paused" ? (
                            <Play className="size-3.5" />
                          ) : (
                            <Pause className="size-3.5" />
                          )}
                        </Button>
                        {stalled && (
                          <Button
                            size="icon"
                            variant="ghost"
                            className="size-7 text-amber-500 hover:text-amber-600"
                            title="重新尝试下载停滞文件"
                            disabled={operatingId === file.uniqueId}
                            onClick={() => handleRetry(file)}
                          >
                            <RefreshCw className="size-3.5" />
                          </Button>
                        )}
                        <Button
                          size="icon"
                          variant="ghost"
                          className="size-7 text-destructive/80 hover:bg-destructive/10 hover:text-destructive"
                          title="取消下载"
                          disabled={operatingId === file.uniqueId}
                          onClick={() => handleCancel(file)}
                        >
                          <X className="size-3.5" />
                        </Button>
                      </div>
                    </div>
                  </div>
                  <Progress
                    value={progress}
                    className="h-1.5"
                    aria-label={`${file.fileName} download progress`}
                  />
                </div>
              );
            })}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function Metric({
  label,
  value,
  warning = false,
}: {
  label: string;
  value: string;
  warning?: boolean;
}) {
  return (
    <div className="rounded-md border bg-muted/20 p-2.5">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p
        className={`mt-1 truncate font-mono text-sm font-semibold ${
          warning ? "text-destructive" : "text-foreground"
        }`}
      >
        {value}
      </p>
    </div>
  );
}
