"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import useSWR from "swr";
import prettyBytes from "pretty-bytes";
import { Activity, Download, ListChecks, TriangleAlert } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import { Button } from "@/components/ui/button";
import { useWebsocket } from "@/hooks/use-websocket";
import { useSettings } from "@/hooks/use-settings";
import { type TDFile, type TelegramFile } from "@/lib/types";
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
  if (seconds < 60) return `${Math.ceil(seconds)}s`;
  if (seconds < 3_600) return `${Math.ceil(seconds / 60)}m`;
  if (seconds < 86_400) return `${(seconds / 3_600).toFixed(1)}h`;
  return `${(seconds / 86_400).toFixed(1)}d`;
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
  const downloadsRef = useRef<TrackedDownload[]>([]);
  const samples = useRef(new Map<string, FileSample>());

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
      void mutate();
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
      void mutate();
      return;
    }

    const existing = downloadsRef.current.find(
      (download) => download.uniqueId === uniqueId,
    );
    if (!existing) {
      if (file.local.isDownloadingActive) void mutate();
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
  }, [lastJsonMessage, mutate]);

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
    <Card className="mx-auto mb-8 max-w-5xl">
      <CardHeader className="gap-3 pb-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <CardTitle className="flex items-center gap-2 text-lg">
              <Activity className="size-5 text-blue-500" />
              Active downloads
            </CardTitle>
            <p className="mt-1 text-sm text-muted-foreground">
              Live speed, traffic, queue and per-file progress
            </p>
          </div>
          <Button asChild variant="outline" size="sm">
            <Link href="/files">View all files</Link>
          </Button>
        </div>

        <div className="grid grid-cols-2 gap-2 md:grid-cols-5">
          <Metric
            label="Download slots"
            value={`${overview.downloading} / ${overview.downloadLimit}`}
          />
          <Metric
            label="Download speed"
            value={`${prettyBytes(downloadActivity.speed, speedOptions)}/s`}
            warning={aggregateStalled}
          />
          <Metric
            label="Current traffic"
            value={`${prettyBytes(downloadActivity.downloadedSize)} / ${prettyBytes(downloadActivity.totalSize || overview.activeSize)}`}
          />
          <Metric
            label="Session traffic"
            value={prettyBytes(downloadActivity.sessionDownloadedBytes)}
          />
          <Metric
            label="Estimated time"
            value={formatDuration(estimatedSeconds)}
          />
        </div>

        <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <ListChecks className="size-4" />
          <span>{overview.queued} queued</span>
          <span>·</span>
          <span>{prettyBytes(overview.queuedSize)}</span>
          {aggregateStalled && (
            <Badge variant="destructive" className="gap-1">
              <TriangleAlert className="size-3" />
              Possibly stalled
            </Badge>
          )}
        </div>
      </CardHeader>

      <CardContent className="pt-0">
        {error ? (
          <div className="rounded-md border border-destructive/40 p-4 text-sm text-destructive">
            Failed to load active downloads
          </div>
        ) : downloads.length === 0 ? (
          <div className="flex min-h-24 items-center justify-center rounded-md border border-dashed text-sm text-muted-foreground">
            No active downloads. Waiting tasks will be dispatched automatically.
          </div>
        ) : (
          <div className="space-y-3">
            {downloads.map((file) => {
              const progress = percentage(file.downloadedSize, file.size);
              const stalled = now - file.lastProgressAt > STALLED_AFTER_MILLIS;
              return (
                <div key={file.uniqueId} className="rounded-md border p-3">
                  <div className="mb-2 flex flex-wrap items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="truncate font-medium">
                        {file.fileName || `${file.type} #${file.id}`}
                      </p>
                      <p className="mt-1 text-xs text-muted-foreground">
                        {prettyBytes(file.downloadedSize)} /{" "}
                        {prettyBytes(file.size)}
                      </p>
                    </div>
                    <div className="flex items-center gap-2 text-xs">
                      {stalled && (
                        <Badge variant="destructive">Possibly stalled</Badge>
                      )}
                      <Badge variant="secondary">
                        <Download className="mr-1 size-3" />
                        {prettyBytes(stalled ? 0 : file.speed, speedOptions)}/s
                      </Badge>
                      <Badge variant="outline">{progress.toFixed(1)}%</Badge>
                    </div>
                  </div>
                  <Progress
                    value={progress}
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
    <div className="rounded-md border bg-muted/35 p-3">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p
        className={`mt-1 truncate font-mono text-sm font-semibold ${warning ? "text-destructive" : ""}`}
      >
        {value}
      </p>
    </div>
  );
}
