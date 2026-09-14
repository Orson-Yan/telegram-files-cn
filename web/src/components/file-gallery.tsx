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
  FileIcon,
  Film,
  FolderSync,
  HardDrive,
  ImageIcon,
  Loader2,
  Play,
} from "lucide-react";
import prettyBytes from "pretty-bytes";
import { cn } from "@/lib/utils";
import Image from "next/image";
import { format } from "date-fns";
import { TooltipWrapper } from "@/components/ui/tooltip";

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
  // Aggregate files into Post Groups
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
          <p className="text-sm font-medium">No media posts found in current filter</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {postGroups.map((group) => (
            <PostCard
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
            {isLoading ? "Loading more..." : "Load more posts"}
          </Button>
        </div>
      )}
    </div>
  );
}

function PostCard({
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
  const [expanded, setExpanded] = useState(false);
  const groupFileIds = useMemo(() => group.files.map((f) => f.id), [group.files]);
  const isAllSelected = groupFileIds.every((id) => selectedFiles.has(id));
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
        "group/card flex flex-col justify-between overflow-hidden rounded-xl border bg-card/80 shadow-sm transition-all duration-200 hover:shadow-md hover:border-border/80 backdrop-blur-sm",
        isAllSelected && "ring-2 ring-primary/60 border-primary/50",
      )}
    >
      {/* Post Header */}
      <div className="flex items-center justify-between border-b bg-muted/20 px-3.5 py-2.5 text-xs text-muted-foreground">
        <div className="flex min-w-0 items-center gap-1.5 font-medium">
          <Calendar className="size-3.5 shrink-0 text-primary/70" />
          <span className="truncate">{formatPostDate(group.date)}</span>
        </div>
        <div className="flex items-center gap-2">
          {hasMultipleMedia && (
            <Badge variant="secondary" className="px-1.5 py-0 text-[10px] font-normal">
              {group.files.length} items
            </Badge>
          )}
          <Checkbox
            checked={isAllSelected ? true : isPartiallySelected ? "indeterminate" : false}
            onCheckedChange={(checked) => handleGroupSelect(!!checked)}
            className="size-4 rounded"
            aria-label="Select group"
          />
        </div>
      </div>

      {/* Media Content Grid */}
      <div className="p-3">
        {group.files.length === 1 ? (
          <SingleMediaItem
            file={group.files[0]!}
            isSelected={selectedFiles.has(group.files[0]!.id)}
            onSelect={() => onSelectFile(group.files[0]!.id)}
            onClick={() => onFileClick(group.files[0]!)}
          />
        ) : (
          <div
            className={cn(
              "grid gap-2 overflow-hidden rounded-lg",
              group.files.length === 2 && "grid-cols-2",
              group.files.length >= 3 && "grid-cols-2",
            )}
          >
            {group.files.slice(0, 4).map((file, idx) => {
              const isLastOfFour = idx === 3 && group.files.length > 4;
              return (
                <div key={file.id} className="relative">
                  <AlbumThumbItem
                    file={file}
                    isSelected={selectedFiles.has(file.id)}
                    onClick={() => onFileClick(file)}
                  />
                  {isLastOfFour && (
                    <div
                      onClick={() => onFileClick(file)}
                      className="absolute inset-0 flex cursor-pointer items-center justify-center rounded-md bg-black/65 text-sm font-bold text-white backdrop-blur-[2px] transition hover:bg-black/75"
                    >
                      +{group.files.length - 3}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Post Caption Text (if present) */}
      {group.caption && (
        <div className="px-3.5 pb-3">
          <p
            className={cn(
              "text-xs leading-relaxed text-foreground/80 break-words",
              !expanded && "line-clamp-3",
            )}
          >
            {group.caption}
          </p>
          {group.caption.length > 90 && (
            <button
              onClick={() => setExpanded(!expanded)}
              className="mt-1 flex items-center gap-0.5 text-[11px] font-medium text-primary hover:underline"
            >
              {expanded ? (
                <>
                  <span>Less</span>
                  <ChevronUp className="size-3" />
                </>
              ) : (
                <>
                  <span>Read full</span>
                  <ChevronDown className="size-3" />
                </>
              )}
            </button>
          )}
        </div>
      )}

      {/* Footer Info / Summary */}
      <div className="mt-auto flex items-center justify-between border-t bg-muted/10 px-3.5 py-2 text-[11px] text-muted-foreground">
        <span className="truncate">Msg #{group.messageId}</span>
        <span className="font-mono">
          {prettyBytes(group.files.reduce((acc, f) => acc + (f.size || 0), 0))}
        </span>
      </div>
    </div>
  );
}

function SingleMediaItem({
  file,
  isSelected,
  onSelect,
  onClick,
}: {
  file: TelegramFile;
  isSelected: boolean;
  onSelect: () => void;
  onClick: () => void;
}) {
  const isCompleted = file.downloadStatus === "completed";
  const isVideo = file.type === "video";
  const isPhoto = file.type === "photo";

  return (
    <div
      className="group/item relative flex aspect-video w-full cursor-pointer items-center justify-center overflow-hidden rounded-lg border bg-muted/30 transition hover:opacity-95"
      onClick={onClick}
    >
      {file.thumbnail ? (
        <Image
          src={`data:image/jpeg;base64,${file.thumbnail}`}
          alt={file.fileName || "Media"}
          fill
          unoptimized
          className="object-cover transition-transform duration-300 group-hover/item:scale-105"
        />
      ) : isVideo ? (
        <Film className="size-10 stroke-1 text-muted-foreground/60" />
      ) : isPhoto ? (
        <ImageIcon className="size-10 stroke-1 text-muted-foreground/60" />
      ) : (
        <FileIcon className="size-10 stroke-1 text-muted-foreground/60" />
      )}

      {/* Video Overlay Play Button */}
      {isVideo && (
        <div className="absolute inset-0 flex items-center justify-center bg-black/20 transition group-hover/item:bg-black/35">
          <div className="flex size-11 items-center justify-center rounded-full bg-black/60 text-white shadow-lg backdrop-blur-sm transition-transform group-hover/item:scale-110">
            <Play className="ml-0.5 size-5 fill-current" />
          </div>
        </div>
      )}

      {/* Badges Overlay */}
      <div className="absolute bottom-2 left-2 right-2 flex items-center justify-between gap-2">
        <Badge
          variant="secondary"
          className="bg-black/70 px-1.5 py-0.5 text-[10px] font-medium text-white backdrop-blur-sm"
        >
          {isVideo ? "VIDEO" : isPhoto ? "PHOTO" : "FILE"}
        </Badge>
        {isCompleted ? (
          <Badge
            variant="default"
            className="flex items-center gap-1 bg-emerald-600/90 px-1.5 py-0.5 text-[10px] text-white backdrop-blur-sm"
          >
            <CheckCircle2 className="size-2.5" />
            <span>NAS</span>
          </Badge>
        ) : file.downloadStatus === "downloading" ? (
          <Badge
            variant="outline"
            className="flex items-center gap-1 bg-primary/80 px-1.5 py-0.5 text-[10px] text-primary-foreground backdrop-blur-sm"
          >
            <Loader2 className="size-2.5 animate-spin" />
            <span>Downloading</span>
          </Badge>
        ) : null}
      </div>
    </div>
  );
}

function AlbumThumbItem({
  file,
  isSelected,
  onClick,
}: {
  file: TelegramFile;
  isSelected: boolean;
  onClick: () => void;
}) {
  const isVideo = file.type === "video";
  const isCompleted = file.downloadStatus === "completed";

  return (
    <div
      onClick={onClick}
      className="group/thumb relative aspect-square w-full cursor-pointer overflow-hidden rounded-md border bg-muted/40 transition hover:opacity-90"
    >
      {file.thumbnail ? (
        <Image
          src={`data:image/jpeg;base64,${file.thumbnail}`}
          alt={file.fileName || "Thumb"}
          fill
          unoptimized
          className="object-cover transition-transform duration-300 group-hover/thumb:scale-105"
        />
      ) : (
        <div className="flex h-full items-center justify-center text-muted-foreground/50">
          {isVideo ? <Film className="size-6" /> : <ImageIcon className="size-6" />}
        </div>
      )}

      {isVideo && (
        <div className="absolute inset-0 flex items-center justify-center bg-black/25">
          <Play className="size-5 fill-white text-white drop-shadow-md" />
        </div>
      )}

      {isCompleted && (
        <div className="absolute right-1 top-1">
          <CheckCircle2 className="size-3.5 fill-emerald-500 text-white" />
        </div>
      )}
    </div>
  );
}
