"use client";

import React, { useMemo, useState } from "react";
import { type TelegramFile } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Checkbox } from "@/components/ui/checkbox";
import { Button } from "@/components/ui/button";
import {
  Calendar,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Download,
  Film,
  HardDrive,
  ImageIcon,
  Loader2,
  MonitorPlay,
  Play,
} from "lucide-react";
import prettyBytes from "pretty-bytes";
import { cn } from "@/lib/utils";
import { format } from "date-fns";
import { FileThumb } from "@/components/file-thumb";
import { ExternalPlayerDropdown } from "@/components/external-players";
import { useLanguage } from "@/i18n/language-provider";

interface FileGalleryProps {
  files: TelegramFile[];
  selectedFiles: Set<number>;
  onSelectFile: (id: number) => void;
  onSelectGroup?: (fileIds: number[], select: boolean) => void;
  onFileClick: (file: TelegramFile) => void;
  isLoading?: boolean;
  hasMore?: boolean;
  onLoadMore?: () => void;
}

interface PostGroup {
  key: string;
  messageId: number;
  chatId: number;
  date: number;
  caption?: string;
  files: TelegramFile[];
}

function formatPostDate(epochSeconds: number): string {
  if (!epochSeconds || epochSeconds <= 0) return "Unknown Date";
  try {
    return format(new Date(epochSeconds * 1000), "yyyy-MM-dd HH:mm:ss");
  } catch {
    return "Unknown Date";
  }
}

export default function FileGallery({
  files,
  selectedFiles,
  onSelectFile,
  onSelectGroup,
  onFileClick,
  isLoading,
  hasMore,
  onLoadMore,
}: FileGalleryProps) {
  const { t } = useLanguage();

  // Aggregate files into Post Groups (Album / Single Message)
  const postGroups = useMemo(() => {
    const groups: PostGroup[] = [];
    const groupMap = new Map<string, PostGroup>();

    for (const file of files) {
      let key = "";
      if (file.mediaAlbumId && file.mediaAlbumId !== "0") {
        key = `${file.chatId}_album_${file.mediaAlbumId}`;
      } else {
        key = `${file.chatId}_msg_${file.messageId}`;
      }

      let group = groupMap.get(key);
      if (!group) {
        group = {
          key,
          messageId: file.messageId,
          chatId: file.chatId,
          date: file.date,
          caption: file.caption,
          files: [],
        };
        groupMap.set(key, group);
        groups.push(group);
      } else if (!group.caption && file.caption) {
        group.caption = file.caption;
      }
      group.files.push(file);
    }

    return groups;
  }, [files]);

  return (
    <div className="flex flex-col gap-6 pb-12">
      {postGroups.length === 0 && !isLoading ? (
        <div className="flex h-64 flex-col items-center justify-center rounded-xl border border-dashed text-muted-foreground">
          <ImageIcon className="mb-2 size-10 stroke-1 text-muted-foreground/50" />
          <p className="text-sm font-medium">
            {t("No media posts found in current filter")}
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          {postGroups.map((group) => (
            <PostTimelineNode
              key={group.key}
              group={group}
              selectedFiles={selectedFiles}
              onSelectFile={onSelectFile}
              onSelectGroup={onSelectGroup}
              onFileClick={onFileClick}
            />
          ))}
        </div>
      )}

      {/* Infinite Scroll / Load More Trigger */}
      {hasMore && (
        <div className="flex justify-center py-6">
          <Button
            variant="outline"
            onClick={onLoadMore}
            disabled={isLoading}
            className="gap-2 px-6"
          >
            {isLoading && <Loader2 className="size-4 animate-spin" />}
            {isLoading ? t("Loading more...") : t("Load more posts")}
          </Button>
        </div>
      )}
    </div>
  );
}

function PostTimelineNode({
  group,
  selectedFiles,
  onSelectFile,
  onSelectGroup,
  onFileClick,
}: {
  group: PostGroup;
  selectedFiles: Set<number>;
  onSelectFile: (id: number) => void;
  onSelectGroup?: (fileIds: number[], select: boolean) => void;
  onFileClick: (file: TelegramFile) => void;
}) {
  const { t } = useLanguage();
  const [expanded, setExpanded] = useState(false);
  const groupFileIds = useMemo(() => group.files.map((f) => f.id), [group.files]);
  const isAllSelected = groupFileIds.length > 0 && groupFileIds.every((id) => selectedFiles.has(id));
  const isPartiallySelected =
    !isAllSelected && groupFileIds.some((id) => selectedFiles.has(id));

  const handleGroupSelect = (checked: boolean) => {
    if (onSelectGroup) {
      onSelectGroup(groupFileIds, checked);
    } else {
      groupFileIds.forEach((id) => {
        if (checked !== selectedFiles.has(id)) {
          onSelectFile(id);
        }
      });
    }
  };

  const hasMultipleMedia = group.files.length > 1;

  return (
    <div
      className={cn(
        "group/card flex flex-col justify-between overflow-hidden rounded-xl border bg-card/90 shadow-sm transition-all duration-200 hover:shadow-md hover:border-border backdrop-blur-sm",
        isAllSelected && "ring-2 ring-primary/60 border-primary/50",
      )}
    >
      {/* Node Header */}
      <div className="flex items-center justify-between border-b bg-muted/25 px-4 py-3 text-xs text-muted-foreground">
        <div className="flex min-w-0 items-center gap-2 font-medium">
          <Calendar className="size-3.5 shrink-0 text-primary/80" />
          <span className="truncate">{formatPostDate(group.date)}</span>
          <span className="text-[11px] text-muted-foreground/70">
            #{group.messageId}
          </span>
        </div>
        <div className="flex items-center gap-2.5">
          {hasMultipleMedia && (
            <Badge
              variant="secondary"
              className="px-2 py-0.5 text-[11px] font-medium"
            >
              {group.files.length} {t("items")}
            </Badge>
          )}
          <div className="flex items-center gap-1.5">
            <span className="text-[11px] text-muted-foreground hidden sm:inline">
              {t("Select all")}
            </span>
            <Checkbox
              checked={isAllSelected ? true : isPartiallySelected ? "indeterminate" : false}
              onCheckedChange={(checked) => handleGroupSelect(!!checked)}
              className="size-4 rounded"
              aria-label="Select post group"
            />
          </div>
        </div>
      </div>

      {/* Media Grid: Fully unrolls and renders all photos/videos without truncation */}
      <div className="p-3.5">
        {group.files.length === 1 ? (
          // Single Large Media
          <GalleryItem
            file={group.files[0]!}
            layout="single"
            isSelected={selectedFiles.has(group.files[0]!.id)}
            onSelect={() => onSelectFile(group.files[0]!.id)}
            onClick={() => onFileClick(group.files[0]!)}
          />
        ) : (
          // Multi-item Adaptive Full Grid (Displays ALL media items cleanly)
          <div
            className={cn(
              "grid gap-2.5 overflow-hidden rounded-lg",
              group.files.length === 2 && "grid-cols-2",
              group.files.length === 3 && "grid-cols-3",
              group.files.length >= 4 && "grid-cols-2 sm:grid-cols-3 md:grid-cols-4",
            )}
          >
            {group.files.map((file) => (
              <GalleryItem
                key={file.id}
                file={file}
                layout="grid"
                isSelected={selectedFiles.has(file.id)}
                onSelect={() => onSelectFile(file.id)}
                onClick={() => onFileClick(file)}
              />
            ))}
          </div>
        )}
      </div>

      {/* Post Caption Text (if present) */}
      {group.caption && (
        <div className="px-4 pb-3">
          <p
            className={cn(
              "text-xs leading-relaxed text-foreground/85 break-words whitespace-pre-wrap",
              !expanded && "line-clamp-3",
            )}
          >
            {group.caption}
          </p>
          {group.caption.length > 90 && (
            <button
              onClick={() => setExpanded(!expanded)}
              className="mt-1.5 flex items-center gap-0.5 text-[11px] font-medium text-primary hover:underline"
            >
              {expanded ? (
                <>
                  <span>{t("Less")}</span>
                  <ChevronUp className="size-3" />
                </>
              ) : (
                <>
                  <span>{t("Read full")}</span>
                  <ChevronDown className="size-3" />
                </>
              )}
            </button>
          )}
        </div>
      )}

      {/* Node Footer Info / Summary */}
      <div className="mt-auto flex items-center justify-between border-t bg-muted/10 px-4 py-2.5 text-[11px] text-muted-foreground">
        <span className="truncate">
          {group.files.filter((f) => f.type === "video").length > 0 && (
            <span className="mr-2">
              🎬 {group.files.filter((f) => f.type === "video").length} {t("videos")}
            </span>
          )}
          {group.files.filter((f) => f.type === "photo").length > 0 && (
            <span>
              🖼️ {group.files.filter((f) => f.type === "photo").length} {t("photos")}
            </span>
          )}
        </span>
        <span className="font-mono font-medium">
          {prettyBytes(group.files.reduce((acc, f) => acc + (f.size || 0), 0))}
        </span>
      </div>
    </div>
  );
}

function GalleryItem({
  file,
  layout,
  isSelected,
  onSelect,
  onClick,
}: {
  file: TelegramFile;
  layout: "single" | "grid";
  isSelected: boolean;
  onSelect: () => void;
  onClick: () => void;
}) {
  const isVideo = file.type === "video";
  const isPhoto = file.type === "photo";
  const isCompleted = file.downloadStatus === "completed" || !!file.localPath;
  const isDownloading = file.downloadStatus === "downloading";

  return (
    <div
      className={cn(
        "group/item relative cursor-pointer overflow-hidden rounded-lg border bg-muted/30 transition-all duration-200 hover:opacity-95 hover:shadow",
        layout === "single" ? "aspect-video w-full" : "aspect-square w-full",
        isSelected && "ring-2 ring-primary border-primary",
      )}
      onClick={onClick}
    >
      {/* High-Resolution Progressive Thumbnail */}
      <FileThumb
        file={file}
        className="h-full w-full object-cover transition-transform duration-300 group-hover/item:scale-105"
      />

      {/* Video Overlay Play Button */}
      {isVideo && (
        <div className="absolute inset-0 flex items-center justify-center bg-black/20 transition group-hover/item:bg-black/35">
          <div className="flex size-10 items-center justify-center rounded-full bg-black/60 text-white shadow-lg backdrop-blur-sm transition-transform group-hover/item:scale-110">
            <Play className="ml-0.5 size-4.5 fill-current" />
          </div>
        </div>
      )}

      {/* Top Left Item Selection Checkbox */}
      <div
        className={cn(
          "absolute top-2 left-2 z-20 transition-opacity",
          isSelected ? "opacity-100" : "opacity-0 group-hover/item:opacity-100",
        )}
        onClick={(e) => {
          e.stopPropagation();
          onSelect();
        }}
      >
        <Checkbox
          checked={isSelected}
          onCheckedChange={onSelect}
          className="size-4 rounded bg-background/90 border-white/60 shadow-sm"
          aria-label="Select item"
        />
      </div>

      {/* Top Right Quick Actions: External Player for Videos */}
      {isVideo && isCompleted && (
        <div
          className="absolute top-2 right-2 z-20 opacity-0 group-hover/item:opacity-100 transition-opacity"
          onClick={(e) => e.stopPropagation()}
        >
          <ExternalPlayerDropdown
            file={file}
            trigger={
              <Button
                size="icon"
                variant="ghost"
                className="size-7 rounded-full bg-black/65 text-white/90 shadow hover:bg-black/85 hover:text-white"
                title="Play in external player (PotPlayer, VLC, Infuse, etc.)"
              >
                <MonitorPlay className="size-3.5 text-amber-400" />
              </Button>
            }
          />
        </div>
      )}

      {/* Bottom Status Badges */}
      <div className="absolute bottom-2 left-2 right-2 z-10 flex items-center justify-between gap-1.5 pointer-events-none">
        <Badge
          variant="secondary"
          className="bg-black/75 px-1.5 py-0.5 text-[9px] font-medium text-white backdrop-blur-sm"
        >
          {isVideo ? "VIDEO" : isPhoto ? "PHOTO" : "FILE"}
        </Badge>

        <div className="flex items-center gap-1">
          {isCompleted && (
            <Badge
              variant="default"
              className="flex items-center gap-1 bg-emerald-600/90 px-1.5 py-0.5 text-[9px] text-white backdrop-blur-sm"
            >
              <CheckCircle2 className="size-2.5" />
              <span>NAS</span>
            </Badge>
          )}
          {isDownloading && (
            <Badge
              variant="outline"
              className="flex items-center gap-1 bg-primary/85 px-1.5 py-0.5 text-[9px] text-primary-foreground backdrop-blur-sm"
            >
              <Loader2 className="size-2.5 animate-spin" />
            </Badge>
          )}
        </div>
      </div>
    </div>
  );
}
