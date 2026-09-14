import React from "react";
import useSWR from "swr";
import { ExternalLink, GitFork, Github, RefreshCw, Sparkles } from "lucide-react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import Link from "next/link";
import TGDuck16HeyOut from "@/components/animations/tg-duck16_hey_out.json";
import dynamic from "next/dynamic";
import { isVersionNewer } from "@/lib/version";

interface VersionData {
  version: string;
}

interface GitHubReleaseData {
  tag_name: string;
}

const fetcher = (url: string) => fetch(url).then((res) => res.json());

const Lottie = dynamic(() => import("lottie-react"), { ssr: false });

function VersionBadge({
  label,
  value,
  error,
  isLoading,
}: {
  label: string;
  value?: string;
  error?: Error;
  isLoading: boolean;
}) {
  return (
    <div className="rounded-md border bg-background/80 p-3 shadow-sm backdrop-blur">
      <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
        {label}
      </p>
      <div className="mt-2">
        {error ? (
          <Badge variant="destructive">Unavailable</Badge>
        ) : isLoading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <RefreshCw className="size-4 animate-spin" />
            Loading
          </div>
        ) : (
          <Badge variant="secondary" className="font-mono">
            {value}
          </Badge>
        )}
      </div>
    </div>
  );
}

export default function About() {
  const { data: apiData, error: apiError } = useSWR<VersionData, Error>(
    "/version",
  );
  const { data: githubData, error: githubError } = useSWR<
    GitHubReleaseData,
    Error
  >(
    "https://api.github.com/repos/Orson-Yan/telegram-files-cn/releases/latest",
    fetcher,
  );

  const projectInfo = {
    repository: "https://github.com/Orson-Yan/telegram-files-cn",
    author: "Orson-Yan",
    upstreamRepository: "https://github.com/jarvis2f/telegram-files",
    upstreamAuthor: "Jarvis2f",
  };

  const currentVersion = apiData?.version;
  const latestVersion = githubData?.tag_name;
  const isNewVersionAvailable =
    Boolean(currentVersion && latestVersion) &&
    isVersionNewer(latestVersion!, currentVersion!);

  return (
    <div className="flex min-h-full items-center justify-center p-1 md:p-6">
      <Card className="relative w-full max-w-3xl overflow-hidden border-0 bg-[radial-gradient(circle_at_12%_18%,_hsl(var(--telegram-flow-1)/0.34),_transparent_32%),radial-gradient(circle_at_88%_8%,_hsl(var(--chart-4)/0.34),_transparent_30%),linear-gradient(135deg,_hsl(var(--background)),_hsl(var(--muted))_52%,_hsl(var(--telegram-flow-2)/0.18))] shadow-xl">
        <CardHeader className="relative gap-4 p-6 pb-3 md:p-8 md:pb-4">
          <div className="flex flex-col gap-5 md:flex-row md:items-start md:justify-between">
            <div className="min-w-0">
              <div className="mb-3 flex flex-wrap items-center gap-2">
                <Badge variant="secondary">
                  telegram-files-cn
                </Badge>
                <Badge variant="outline" className="gap-1 text-xs font-normal">
                  <GitFork className="size-3 text-muted-foreground" />
                  Forked from jarvis2f/telegram-files
                </Badge>
              </div>
              <CardTitle className="text-2xl tracking-normal md:text-3xl">
                Telegram Files (Enhanced Edition)
              </CardTitle>
              <CardDescription className="mt-2 max-w-xl text-sm md:text-base">
                A self-hosted Telegram file downloader enhanced with complete Chinese localization,
                cloud forwarding, and local organization. Forked with gratitude from{" "}
                <Link
                  href={projectInfo.upstreamRepository}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="font-medium text-primary underline underline-offset-4 hover:text-primary/80"
                >
                  jarvis2f/telegram-files
                </Link>
                .
              </CardDescription>
            </div>
            <div className="relative mx-auto size-28 shrink-0 md:mx-0">
              <div className="absolute inset-2 rounded-full bg-background/70 blur-sm" />
              <Lottie
                className="relative size-28"
                animationData={TGDuck16HeyOut}
                loop={true}
              />
            </div>
          </div>
        </CardHeader>
        <CardContent className="relative flex flex-col gap-4 p-6 pt-2 md:p-8 md:pt-2">
          <div className="grid gap-3 md:grid-cols-3">
            <div className="rounded-md border bg-background/80 p-3 shadow-sm backdrop-blur">
              <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                Maintainer
              </p>
              <p className="mt-2 font-medium">{projectInfo.author}</p>
            </div>
            <VersionBadge
              label="Current version"
              value={currentVersion}
              error={apiError}
              isLoading={!apiError && !apiData}
            />
            <VersionBadge
              label="Latest release"
              value={latestVersion}
              error={githubError}
              isLoading={!githubError && !githubData}
            />
          </div>

          {isNewVersionAvailable ? (
            <div className="flex flex-col gap-3 rounded-md border bg-background/85 p-4 shadow-sm backdrop-blur md:flex-row md:items-center md:justify-between">
              <div className="flex items-start gap-3">
                <span className="flex size-9 shrink-0 items-center justify-center rounded-md border bg-muted text-muted-foreground">
                  <Sparkles className="size-4" />
                </span>
                <div>
                  <p className="font-medium">New release available</p>
                  <p className="mt-1 text-sm text-muted-foreground">
                    Version {latestVersion} is newer than your current{" "}
                    {currentVersion} install.
                  </p>
                </div>
              </div>
              <Button asChild size="sm">
                <Link
                  href={`${projectInfo.repository}/releases/latest`}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  View release
                  <ExternalLink />
                </Link>
              </Button>
            </div>
          ) : (
            currentVersion &&
            latestVersion && (
              <div className="rounded-md border bg-background/70 p-3 text-sm text-muted-foreground shadow-sm backdrop-blur">
                Your installation is at least as recent as the latest published
                release.
              </div>
            )
          )}

          <div className="flex flex-wrap items-center justify-end gap-2 pt-2">
            <Button asChild variant="outline" size="sm">
              <Link
                href={projectInfo.upstreamRepository}
                target="_blank"
                rel="noopener noreferrer"
              >
                <GitFork className="mr-1.5 size-4" />
                Upstream (jarvis2f)
              </Link>
            </Button>
            <Button asChild size="sm">
              <Link
                href={projectInfo.repository}
                target="_blank"
                rel="noopener noreferrer"
              >
                <Github className="mr-1.5 size-4" />
                GitHub (Orson-Yan)
              </Link>
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
