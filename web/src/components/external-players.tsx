"use client";

import React from "react";
import { type TelegramFile } from "@/lib/types";
import { getApiUrl } from "@/lib/api";
import { useAdminSession } from "@/hooks/use-admin-session";
import { toast } from "@/hooks/use-toast";
import { useLanguage } from "@/i18n/language-provider";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import {
  Copy,
  MonitorPlay,
  Play,
  VideoOff,
} from "lucide-react";
import { cn } from "@/lib/utils";

export function getFileStreamUrl(
  file: TelegramFile,
  sessionToken?: string,
): string {
  if (typeof window === "undefined" || !file || !file.uniqueId) return "";
  try {
    const base = getApiUrl() || "";
    const path = `${base}/${file.telegramId || 0}/file/${file.uniqueId}`;
    const absoluteUrl = new URL(path, window.location.origin);
    if (sessionToken) {
      absoluteUrl.searchParams.set("token", sessionToken);
    }
    return absoluteUrl.href;
  } catch (err) {
    console.warn("Failed to construct stream URL:", err);
    return "";
  }
}

export interface PlayerOption {
  id: string;
  name: string;
  platform: string;
  getProtocolUrl: (streamUrl: string) => string;
}

export const EXTERNAL_PLAYERS: PlayerOption[] = [
  {
    id: "potplayer",
    name: "PotPlayer",
    platform: "Windows",
    getProtocolUrl: (url) => `potplayer://${url}`,
  },
  {
    id: "vlc",
    name: "VLC",
    platform: "Win / Mac / Linux / Mobile",
    getProtocolUrl: (url) => `vlc://${url}`,
  },
  {
    id: "iina",
    name: "IINA",
    platform: "macOS",
    getProtocolUrl: (url) => `iina://weblink?url=${encodeURIComponent(url)}`,
  },
  {
    id: "infuse",
    name: "Infuse",
    platform: "iOS / macOS / Apple TV",
    getProtocolUrl: (url) =>
      `infuse://x-callback-url/play?url=${encodeURIComponent(url)}`,
  },
  {
    id: "mpv",
    name: "MPV",
    platform: "All Platforms",
    getProtocolUrl: (url) => `mpv://${url}`,
  },
  {
    id: "nplayer",
    name: "nPlayer",
    platform: "iOS / Android",
    getProtocolUrl: (url) => `nplayer-${url}`,
  },
];

export function launchPlayer(player: PlayerOption, streamUrl: string) {
  if (!streamUrl) return;
  const protocolUrl = player.getProtocolUrl(streamUrl);
  window.location.href = protocolUrl;
}

export function copyStreamLink(streamUrl: string, message?: string) {
  if (!streamUrl) return;
  void navigator.clipboard.writeText(streamUrl);
  toast({
    title: message || "Stream link copied to clipboard!",
    description: "Paste into PotPlayer, VLC, Infuse or any video player to stream.",
  });
}

interface ExternalPlayerDropdownProps {
  file: TelegramFile;
  trigger?: React.ReactNode;
  align?: "start" | "end" | "center";
}

export function ExternalPlayerDropdown({
  file,
  trigger,
  align = "end",
}: ExternalPlayerDropdownProps) {
  const { t } = useLanguage();
  const { session } = useAdminSession();
  const streamUrl = getFileStreamUrl(file, session?.token);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        {trigger || (
          <Button
            size="icon"
            variant="ghost"
            className="size-7 rounded-full bg-black/50 text-white/90 shadow hover:bg-black/80 hover:text-white"
            title={t("Play in external player")}
          >
            <MonitorPlay className="size-3.5" />
          </Button>
        )}
      </DropdownMenuTrigger>
      <DropdownMenuContent align={align} className="w-56 z-50">
        <DropdownMenuLabel className="flex items-center gap-1.5 text-xs text-muted-foreground font-normal">
          <MonitorPlay className="size-3.5 text-primary" />
          <span>{t("External Players")}</span>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        {EXTERNAL_PLAYERS.map((p) => (
          <DropdownMenuItem
            key={p.id}
            className="flex items-center justify-between py-1.5 cursor-pointer text-xs"
            onClick={(e) => {
              e.stopPropagation();
              launchPlayer(p, streamUrl);
            }}
          >
            <div className="flex items-center gap-2">
              <Play className="size-3 text-primary/80 fill-current" />
              <span className="font-medium">{p.name}</span>
            </div>
            <span className="text-[10px] text-muted-foreground">{p.platform}</span>
          </DropdownMenuItem>
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem
          className="flex items-center gap-2 py-1.5 cursor-pointer text-xs font-medium text-primary"
          onClick={(e) => {
            e.stopPropagation();
            copyStreamLink(streamUrl, t("Stream link copied to clipboard!"));
          }}
        >
          <Copy className="size-3.5" />
          <span>{t("Copy Stream URL")}</span>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

interface ExternalPlayerFallbackCardProps {
  file: TelegramFile;
  className?: string;
  errorMessage?: string;
}

export function ExternalPlayerFallbackCard({
  file,
  className,
  errorMessage,
}: ExternalPlayerFallbackCardProps) {
  const { t } = useLanguage();
  const { session } = useAdminSession();
  const streamUrl = getFileStreamUrl(file, session?.token);

  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center rounded-xl border border-white/10 bg-zinc-950/95 p-6 text-center shadow-2xl backdrop-blur-md",
        className,
      )}
    >
      <div className="mb-3 rounded-full bg-white/10 p-3.5 text-white/80">
        <VideoOff className="size-8 stroke-1" />
      </div>
      <h3 className="text-base font-semibold text-white">
        {t("Browser cannot decode this video format")}
      </h3>
      <p className="mt-1 max-w-md text-xs text-white/60">
        {errorMessage ||
          t(
            "Formats such as HEVC/H.265 10-bit, MKV, or audio tracks like DTS/AC3 require external player decoding.",
          )}
      </p>

      {/* Launcher Buttons */}
      <div className="mt-5 flex flex-wrap items-center justify-center gap-2 max-w-lg">
        {EXTERNAL_PLAYERS.slice(0, 4).map((p) => (
          <Button
            key={p.id}
            size="sm"
            variant="outline"
            className="border-white/20 bg-white/5 text-xs text-white transition hover:bg-white/15"
            onClick={() => launchPlayer(p, streamUrl)}
          >
            <Play className="mr-1.5 size-3 fill-current" />
            {p.name}
          </Button>
        ))}
        <Button
          size="sm"
          variant="secondary"
          className="text-xs font-medium gap-1.5"
          onClick={() =>
            copyStreamLink(streamUrl, t("Stream link copied to clipboard!"))
          }
        >
          <Copy className="size-3.5" />
          <span>{t("Copy Stream URL")}</span>
        </Button>
      </div>
    </div>
  );
}
