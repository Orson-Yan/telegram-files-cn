"use client";

import { DownloadMonitor } from "@/components/download-monitor";
import { PageHeader } from "@/components/page-header";
import { Card, CardContent } from "@/components/ui/card";
import { type DownloadOverviewStatistics } from "@/lib/download-activity";
import { Activity, Loader2, TriangleAlert } from "lucide-react";
import useSWR from "swr";

const REFRESH_INTERVAL_MILLIS = 15_000;

export default function DownloadsPage() {
  const { data, error, isLoading } = useSWR<DownloadOverviewStatistics, Error>(
    "/files/count",
    {
      refreshInterval: REFRESH_INTERVAL_MILLIS,
      refreshWhenHidden: false,
    },
  );

  return (
    <div className="container mx-auto px-4 py-6">
      <PageHeader
        title="Download tasks"
        icon={<Activity className="size-5 text-blue-500" />}
      />

      {error ? (
        <Card className="mx-auto mb-8 max-w-5xl">
          <CardContent className="flex items-center justify-center p-6 text-destructive">
            <TriangleAlert className="mr-2 size-5" />
            Failed to load active downloads
          </CardContent>
        </Card>
      ) : isLoading || !data ? (
        <Card className="mx-auto mb-8 max-w-5xl">
          <CardContent className="flex items-center justify-center p-6 text-muted-foreground">
            <Loader2 className="mr-2 size-5 animate-spin" />
            Loading file counts...
          </CardContent>
        </Card>
      ) : (
        <DownloadMonitor statistics={data} />
      )}
    </div>
  );
}
