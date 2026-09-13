"use client";

import {
  Activity,
  AlertTriangle,
  ArrowRight,
  Check,
  CloudUpload,
  Download,
  FolderOpen,
  FolderSync,
  HardDrive,
  Loader2,
  LogOut,
  MessageSquare,
  Sparkles,
  Users,
  Workflow,
} from "lucide-react";
import { AccountList } from "./account-list";
import { type TelegramAccount } from "@/lib/types";
import { PlatformTelegramIcon } from "@/components/platform-telegram-icon";
import { AccountDialog } from "@/components/account-dialog";
import React, { useState } from "react";
import { Button } from "@/components/ui/button";
import { TooltipWrapper } from "@/components/ui/tooltip";
import useSWR from "swr";
import prettyBytes from "pretty-bytes";
import { Card, CardContent } from "./ui/card";
import { useRouter } from "next/navigation";
import { useAdminSession } from "@/hooks/use-admin-session";
import { PlatformBindingShortcut } from "@/components/platform-binding-shortcut";
import { DotmTriangle2 } from "@/components/ui/dotm-triangle-2";
import {
  type DownloadOverviewStatistics,
  normalizeDownloadOverview,
} from "@/lib/download-activity";
import { useWebsocket } from "@/hooks/use-websocket";
import ThemeToggleButton from "@/components/theme-toggle-button";
import { LanguageToggleButton } from "@/i18n/language-toggle-button";
import { SettingsDialog } from "@/components/settings-dialog";
import { Badge } from "@/components/ui/badge";

interface EmptyStateProps {
  isLoadingAccount?: boolean;
  hasAccounts: boolean;
  accounts?: TelegramAccount[];
  message?: string;
  onSelectAccount?: (accountId: string) => void;
}

export function EmptyState({
  isLoadingAccount,
  hasAccounts,
  accounts = [],
  message,
  onSelectAccount,
}: EmptyStateProps) {
  const { session, logout } = useAdminSession();
  const [loggingOut, setLoggingOut] = useState(false);

  const handleLogout = async () => {
    setLoggingOut(true);
    try {
      await logout();
    } finally {
      setLoggingOut(false);
    }
  };

  if (message) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <div className="mb-4 flex size-16 items-center justify-center rounded-2xl bg-muted/60 text-muted-foreground">
          <MessageSquare className="size-8" />
        </div>
        <h2 className="mb-2 text-2xl font-semibold tracking-tight">{message}</h2>
        <p className="max-w-md text-sm text-muted-foreground">
          Choose a chat from the dropdown menu above to view and manage its files.
        </p>
      </div>
    );
  }

  return (
    <div className="container mx-auto space-y-8 px-4 py-6">
      {/* Top Navigation & Controls Bar */}
      <div className="flex items-center justify-between gap-4 rounded-xl border border-border/60 bg-card/60 p-3.5 shadow-sm backdrop-blur-sm sm:p-4">
        <div className="flex items-center gap-3">
          <div className="flex size-9 items-center justify-center rounded-lg bg-primary/10 text-primary">
            <PlatformTelegramIcon className="size-5" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-base font-semibold tracking-tight sm:text-lg">
                Telegram Files
              </h1>
              <Badge variant="secondary" className="h-5 px-1.5 text-[10px] font-normal">
                Dashboard
              </Badge>
            </div>
            <p className="hidden text-xs text-muted-foreground sm:block">
              Manage accounts, automated downloads and cloud archives
            </p>
          </div>
        </div>

        <div className="flex items-center gap-1 sm:gap-1.5">
          <ThemeToggleButton />
          <LanguageToggleButton />
          <PlatformBindingShortcut />
          <SettingsDialog />

          {session && (
            <TooltipWrapper content="Log out">
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="h-8 w-8 text-muted-foreground hover:text-foreground"
                aria-label="Log out"
                disabled={loggingOut}
                onClick={() => void handleLogout()}
              >
                <LogOut className="size-4" />
              </Button>
            </TooltipWrapper>
          )}
        </div>
      </div>

      {/* Account Workspace (First Priority) */}
      <section className="space-y-3.5">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Users className="size-4 text-primary" />
            <h2 className="text-sm font-semibold tracking-tight text-foreground uppercase tracking-wider">
              Telegram Accounts
            </h2>
            {accounts.length > 0 && (
              <Badge variant="secondary" className="h-5 px-1.5 text-xs font-mono">
                {accounts.length}
              </Badge>
            )}
          </div>
        </div>

        {isLoadingAccount ? (
          <div className="flex min-h-[160px] items-center justify-center rounded-xl border border-dashed border-border/70 bg-card/40">
            <DotmTriangle2
              size={32}
              dotSize={4}
              speed={1.4}
              opacityBase={0.1}
              opacityMid={0.4}
              opacityPeak={0.95}
              ariaLabel="Loading account"
            />
          </div>
        ) : hasAccounts && accounts.length > 0 && onSelectAccount ? (
          <AccountList accounts={accounts} onSelectAccount={onSelectAccount} showAddCard={true} />
        ) : (
          <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border/80 bg-card/30 px-6 py-12 text-center">
            <div className="mb-3 flex size-12 items-center justify-center rounded-full bg-muted">
              <PlatformTelegramIcon className="size-6 text-muted-foreground" />
            </div>
            <h3 className="text-base font-semibold text-foreground">
              No Accounts Found
            </h3>
            <p className="mt-1 max-w-sm text-xs text-muted-foreground">
              Connect a Telegram account to start downloading, organizing, and archiving files.
            </p>
            <div className="mt-4">
              <AccountDialog isAdd={true}>
                <Button size="sm" className="gap-2">
                  <Sparkles className="size-4" />
                  Add First Account
                </Button>
              </AccountDialog>
            </div>
          </div>
        )}
      </section>

      {/* Overview & Feature Hub */}
      <DashboardMetricsAndFeatures />
    </div>
  );
}

type FileCount = DownloadOverviewStatistics;

function DashboardMetricsAndFeatures() {
  const router = useRouter();
  const { downloadActivity } = useWebsocket();
  const { data, error, isLoading } = useSWR<FileCount, Error>(`/files/count`);

  if (error) {
    return (
      <Card className="border-destructive/30">
        <CardContent className="flex items-center justify-center p-6 text-sm text-destructive">
          <AlertTriangle className="mr-2 size-4" />
          Failed to load system metrics
        </CardContent>
      </Card>
    );
  }

  if (isLoading || !data) {
    return (
      <Card className="border-border/60">
        <CardContent className="flex items-center justify-center p-6 text-sm text-muted-foreground">
          <Loader2 className="mr-2 size-4 animate-spin" />
          Loading statistics...
        </CardContent>
      </Card>
    );
  }

  const statistics = normalizeDownloadOverview(data);
  const isDownloading = statistics.downloading > 0;

  return (
    <div className="space-y-6">
      {/* Real-time System Overview Metric Cards */}
      <section className="space-y-3">
        <h2 className="text-xs font-semibold tracking-wider text-muted-foreground uppercase">
          Live System Overview
        </h2>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <OverviewMetric
            icon={<Check className="size-4 text-emerald-500" />}
            label="Downloaded"
            value={statistics.completed.toLocaleString()}
          />
          <OverviewMetric
            icon={
              <div className="relative">
                <Download className="size-4 text-blue-500" />
                {isDownloading && (
                  <span className="absolute -top-1 -right-1 flex size-2">
                    <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-blue-400 opacity-75" />
                    <span className="relative inline-flex size-2 rounded-full bg-blue-500" />
                  </span>
                )}
              </div>
            }
            label="Downloading"
            value={`${statistics.downloading} / ${statistics.downloadLimit}`}
          />
          <OverviewMetric
            icon={<HardDrive className="size-4 text-purple-500" />}
            label="Downloaded size"
            value={prettyBytes(statistics.downloadedSize)}
          />
          <OverviewMetric
            icon={<Activity className="size-4 text-cyan-500" />}
            label="Current speed"
            value={`${prettyBytes(downloadActivity.speed)}/s`}
            highlight={downloadActivity.speed > 0}
          />
        </div>
      </section>

      {/* Feature Navigation Hub */}
      <section className="space-y-3">
        <h2 className="text-xs font-semibold tracking-wider text-muted-foreground uppercase">
          Features & Tools
        </h2>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5">
          {/* 1. Downloads */}
          <FeatureCard
            icon={<Activity className="size-5 text-blue-500" />}
            iconBg="bg-blue-500/10"
            title="Download tasks"
            description="Monitor live speed, queue and active files"
            badge={
              statistics.downloading > 0
                ? `${statistics.downloading} active`
                : undefined
            }
            badgeVariant="default"
            onClick={() => router.push("/downloads")}
          />

          {/* 2. Cloud Archive */}
          <FeatureCard
            icon={<CloudUpload className="size-5 text-sky-500" />}
            iconBg="bg-sky-500/10"
            title="Cloud archive"
            description="Archive channels and chats to cloud storage"
            onClick={() => router.push("/cloud-archive")}
          />

          {/* 3. Local Organize */}
          <FeatureCard
            icon={<FolderSync className="size-5 text-amber-500" />}
            iconBg="bg-amber-500/10"
            title="Local organization"
            description="Auto-sort, deduplicate & rename files"
            onClick={() => router.push("/local-organize")}
          />

          {/* 4. Automations */}
          <FeatureCard
            icon={<Workflow className="size-5 text-violet-500" />}
            iconBg="bg-violet-500/10"
            title="Automations"
            description="Chat listeners and download filters"
            onClick={() => router.push("/automations")}
          />

          {/* 5. All Files */}
          <FeatureCard
            icon={<FolderOpen className="size-5 text-emerald-500" />}
            iconBg="bg-emerald-500/10"
            title="Files"
            description="Browse, batch download and search files"
            onClick={() => router.push("/files")}
          />
        </div>
      </section>
    </div>
  );
}

function OverviewMetric({
  icon,
  label,
  value,
  highlight = false,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  highlight?: boolean;
}) {
  return (
    <div className="flex items-center gap-3 rounded-xl border border-border/50 bg-card p-3.5 shadow-sm transition-colors">
      <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted/60">
        {icon}
      </div>
      <div className="min-w-0 flex-1">
        <span className="block truncate text-xs text-muted-foreground">{label}</span>
        <span
          className={`block truncate font-mono text-sm font-semibold tabular-nums ${
            highlight ? "text-cyan-500 font-bold" : "text-foreground"
          }`}
        >
          {value}
        </span>
      </div>
    </div>
  );
}

function FeatureCard({
  icon,
  iconBg,
  title,
  description,
  badge,
  badgeVariant = "secondary",
  onClick,
}: {
  icon: React.ReactNode;
  iconBg: string;
  title: string;
  description: string;
  badge?: string;
  badgeVariant?: "default" | "secondary" | "outline";
  onClick: () => void;
}) {
  return (
    <Card
      onClick={onClick}
      className="group relative cursor-pointer border-border/70 bg-card transition-all duration-200 hover:-translate-y-0.5 hover:border-primary/50 hover:shadow-md"
    >
      <CardContent className="flex flex-col justify-between gap-3 p-4 h-full">
        <div>
          <div className="mb-3 flex items-center justify-between">
            <div className={`flex size-9 items-center justify-center rounded-lg ${iconBg}`}>
              {icon}
            </div>
            {badge && (
              <Badge variant={badgeVariant} className="text-[10px] h-5 px-1.5 font-normal">
                {badge}
              </Badge>
            )}
          </div>
          <h3 className="font-semibold text-sm text-foreground group-hover:text-primary transition-colors">
            {title}
          </h3>
          <p className="mt-1 text-xs text-muted-foreground leading-relaxed">
            {description}
          </p>
        </div>

        <div className="flex items-center pt-2 text-xs font-medium text-primary opacity-0 transition-opacity duration-200 group-hover:opacity-100">
          <span>Open feature</span>
          <ArrowRight className="ml-1 size-3 transition-transform duration-200 group-hover:translate-x-0.5" />
        </div>
      </CardContent>
    </Card>
  );
}
