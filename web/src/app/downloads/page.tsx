"use client";

import { useState } from "react";
import { DownloadMonitor } from "@/components/download-monitor";
import { ChannelDownloadManager } from "@/components/channel-download-manager";
import { PageHeader } from "@/components/page-header";
import { Card, CardContent } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Badge } from "@/components/ui/badge";
import { type DownloadOverviewStatistics } from "@/lib/download-activity";
import { Activity, Download, Loader2, TriangleAlert } from "lucide-react";
import useSWR from "swr";

const REFRESH_INTERVAL_MILLIS = 15_000;

export default function DownloadsPage() {
  const [tab, setTab] = useState<string>("channels");
  const { data, error, isLoading } = useSWR<DownloadOverviewStatistics, Error>(
    "/files/count",
    {
      refreshInterval: REFRESH_INTERVAL_MILLIS,
      refreshWhenHidden: false,
    },
  );

  const downloadingCount = data?.downloading ?? 0;
  const queuedCount = data?.queued ?? 0;

  return (
    <div className="container mx-auto px-4 py-6">
      <PageHeader
        title="下载任务与监控"
        icon={<Download className="size-5 text-blue-500" />}
        backHref="/files"
        backLabel="返回文件"
        badge={
          data ? (
            <div className="hidden sm:flex flex-wrap items-center gap-2 text-xs">
              {downloadingCount > 0 ? (
                <Badge variant="default" className="gap-1 bg-blue-600 hover:bg-blue-600">
                  <span className="size-1.5 animate-pulse rounded-full bg-white" />
                  正在下载 {downloadingCount} 项
                </Badge>
              ) : null}
              {queuedCount > 0 ? (
                <Badge variant="secondary">
                  排队中 {queuedCount} 项
                </Badge>
              ) : null}
              {data.completed ? (
                <Badge variant="outline">
                  累计完成 {data.completed} 项
                </Badge>
              ) : null}
            </div>
          ) : null
        }
      />

      <Tabs value={tab} onValueChange={setTab} className="space-y-4">
        <TabsList className="grid h-10 w-full max-w-md grid-cols-2">
          <TabsTrigger value="channels" className="gap-2 text-sm">
            <Download className="size-4" />
            频道下载任务
          </TabsTrigger>
          <TabsTrigger value="monitor" className="gap-2 text-sm">
            <Activity className="size-4" />
            活跃下载监控
            {downloadingCount > 0 ? (
              <span className="ml-1 rounded-full bg-blue-500 px-1.5 py-0.2 text-[10px] text-white">
                {downloadingCount}
              </span>
            ) : null}
          </TabsTrigger>
        </TabsList>

        <TabsContent value="channels" className="focus-visible:outline-none">
          <ChannelDownloadManager />
        </TabsContent>

        <TabsContent value="monitor" className="focus-visible:outline-none">
          {error ? (
            <Card className="mx-auto mb-8 max-w-5xl">
              <CardContent className="flex items-center justify-center p-6 text-destructive">
                <TriangleAlert className="mr-2 size-5" />
                加载活跃下载列表失败
              </CardContent>
            </Card>
          ) : isLoading || !data ? (
            <Card className="mx-auto mb-8 max-w-5xl">
              <CardContent className="flex items-center justify-center p-6 text-muted-foreground">
                <Loader2 className="mr-2 size-5 animate-spin" />
                正在加载统计信息...
              </CardContent>
            </Card>
          ) : (
            <DownloadMonitor statistics={data} />
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}
