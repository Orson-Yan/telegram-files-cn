import {
  Activity,
  AlertTriangle,
  ArrowRight,
  Check,
  CloudUpload,
  Download,
  FolderSync,
  HardDrive,
  Loader2,
  LogOut,
  MessageSquare,
  UserPlus,
  Workflow,
} from "lucide-react";
import { AccountList } from "./account-list";
import { type TelegramAccount } from "@/lib/types";
import { PlatformTelegramIcon } from "@/components/platform-telegram-icon";
import { AccountDialog } from "@/components/account-dialog";
import React, { useState } from "react";
import { Button } from "@/components/ui/button";
import { BorderBeam } from "@/components/ui/border-beam";
import { TooltipWrapper } from "@/components/ui/tooltip";
import useSWR from "swr";
import prettyBytes from "pretty-bytes";
import { Card, CardContent } from "./ui/card";
import { useRouter } from "next/navigation";
import useIsMobile from "@/hooks/use-is-mobile";
import { useAdminSession } from "@/hooks/use-admin-session";
import { PlatformBindingShortcut } from "@/components/platform-binding-shortcut";
import { DotmTriangle2 } from "@/components/ui/dotm-triangle-2";
import {
  type DownloadOverviewStatistics,
  normalizeDownloadOverview,
} from "@/lib/download-activity";
import { useWebsocket } from "@/hooks/use-websocket";

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
  const isMobile = useIsMobile();
  const { logout } = useAdminSession();
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
      <div className="flex flex-col items-center">
        <MessageSquare className="mb-4 h-16 w-16 text-muted-foreground" />
        <h2 className="mb-2 text-2xl font-semibold">{message}</h2>
        <p className="text-muted-foreground">
          Choose a chat from the dropdown menu above to view and manage its
          files.
        </p>
      </div>
    );
  }

  return (
    <div className="container mx-auto px-4 py-6">
      <div className="mb-2 flex justify-end">
        <PlatformBindingShortcut />
        <TooltipWrapper content="Log out">
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label="Log out"
            disabled={loggingOut}
            onClick={() => void handleLogout()}
          >
            <LogOut data-icon="inline-start" />
          </Button>
        </TooltipWrapper>
      </div>
      <div className="mb-8 flex flex-col items-center text-center">
        {hasAccounts ? (
          <>
            <PlatformTelegramIcon className="mb-4 size-16 text-foreground" />
            {!isMobile && (
              <>
                <h2 className="mb-2 text-2xl font-semibold">
                  Select an Account
                </h2>
                <p className="mb-4 max-w-md text-muted-foreground">
                  Choose a Telegram account to view and manage your files. You
                  can add more accounts using the button below.
                </p>
              </>
            )}
          </>
        ) : (
          <>
            <PlatformTelegramIcon className="mb-4 size-16 text-foreground" />
            <h2 className="mb-2 text-2xl font-semibold">No Accounts Found</h2>
            <p className="mb-4 max-w-md text-muted-foreground">
              Add a Telegram account to start managing your files. You can add
              multiple accounts and switch between them.
            </p>
          </>
        )}
        <div className="flex items-center justify-center gap-4">
          <AccountDialog isAdd={true}>
            <div className="relative rounded-md">
              <BorderBeam size={60} duration={12} delay={9} />
              <Button variant="outline">
                <UserPlus className="mr-2 h-4 w-4" />
                Add Account
              </Button>
            </div>
          </AccountDialog>
        </div>
      </div>

      <AllFiles />

      {isLoadingAccount && (
        <div className="absolute inset-0 flex items-center justify-center">
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
      )}

      {hasAccounts && accounts.length > 0 && onSelectAccount && (
        <AccountList accounts={accounts} onSelectAccount={onSelectAccount} />
      )}
    </div>
  );
}

type FileCount = DownloadOverviewStatistics;

function AllFiles() {
  const router = useRouter();
  const { downloadActivity } = useWebsocket();
  const { data, error, isLoading } = useSWR<FileCount, Error>(`/files/count`);

  if (error) {
    return (
      <Card className="mx-auto mb-8 max-w-5xl">
        <CardContent className="flex items-center justify-center p-6 text-red-500">
          <AlertTriangle className="mr-2" />
          Failed to load file counts
        </CardContent>
      </Card>
    );
  }

  if (isLoading || !data) {
    return (
      <Card className="mx-auto mb-8 max-w-5xl">
        <CardContent className="flex items-center justify-center p-6 text-gray-500">
          <Loader2 className="mr-2 animate-spin" />
          Loading file counts...
        </CardContent>
      </Card>
    );
  }

  const statistics = normalizeDownloadOverview(data);

  return (
    <Card className="mx-auto mb-8 max-w-5xl">
      <CardContent className="space-y-3 p-3">
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <OverviewMetric
            icon={<Check className="text-green-500" />}
            label="Downloaded"
            value={statistics.completed.toLocaleString()}
          />
          <OverviewMetric
            icon={<Download className="text-blue-500" />}
            label="Downloading"
            value={`${statistics.downloading} / ${statistics.downloadLimit}`}
          />
          <OverviewMetric
            icon={<HardDrive className="text-purple-500" />}
            label="Downloaded size"
            value={prettyBytes(statistics.downloadedSize)}
          />
          <OverviewMetric
            icon={<Activity className="text-cyan-500" />}
            label="Current speed"
            value={`${prettyBytes(downloadActivity.speed)}/s`}
          />
        </div>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5">
          <Button
            className="w-full"
            variant="outline"
            size="sm"
            onClick={() => router.push("/downloads")}
          >
            <Activity data-icon="inline-start" />
            Download tasks
          </Button>
          <Button
            className="w-full"
            variant="outline"
            size="sm"
            onClick={() => router.push("/cloud-archive")}
          >
            <CloudUpload data-icon="inline-start" />
            Cloud archive
          </Button>
          <Button
            className="w-full"
            variant="outline"
            size="sm"
            onClick={() => router.push("/local-organize")}
          >
            <FolderSync data-icon="inline-start" />
            Local organization
          </Button>
          <Button
            className="w-full"
            variant="outline"
            size="sm"
            onClick={() => router.push("/automations")}
          >
            <Workflow data-icon="inline-start" />
            Automations
          </Button>
          <Button
            className="w-full"
            variant="outline"
            size="sm"
            onClick={() => router.push("/files")}
          >
            Files
            <ArrowRight data-icon="inline-end" />
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

function OverviewMetric({
  icon,
  label,
  value,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
}) {
  return (
    <div className="flex min-w-0 items-center gap-3 rounded-lg bg-gray-100 p-3 dark:bg-gray-800">
      <span className="shrink-0">{icon}</span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-xs text-muted-foreground">{label}</span>
        <span className="block min-w-[9ch] whitespace-nowrap text-sm font-semibold tabular-nums">
          {value}
        </span>
      </span>
    </div>
  );
}
