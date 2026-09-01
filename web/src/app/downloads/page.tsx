"use client";

import { DownloadMonitor } from "@/components/download-monitor";
import { PlatformTelegramIcon } from "@/components/platform-telegram-icon";
import ThemeToggleButton from "@/components/theme-toggle-button";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { type DownloadOverviewStatistics } from "@/lib/download-activity";
import { Activity, ArrowLeft, Loader2, TriangleAlert } from "lucide-react";
import Link from "next/link";
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
      <Card className="mb-6">
        <CardContent className="p-4">
          <div className="relative flex items-center justify-between gap-4">
            <Button variant="ghost" size="sm" asChild>
              <Link href="/">
                <ArrowLeft data-icon="inline-start" />
                Home
              </Link>
            </Button>

            <div className="flex min-w-0 items-center gap-2">
              <PlatformTelegramIcon className="size-6 shrink-0" />
              <h3 className="flex items-center gap-2 truncate text-lg font-semibold">
                <Activity className="size-5 text-blue-500" />
                Download tasks
              </h3>
            </div>

            <ThemeToggleButton />
          </div>
        </CardContent>
      </Card>

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
