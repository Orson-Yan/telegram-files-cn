"use client";

import React, { useState } from "react";
import { type TelegramFile } from "@/lib/types";
import { getApiUrl } from "@/lib/api";
import { cn } from "@/lib/utils";
import { EyeOff, FileText, Film, ImageIcon } from "lucide-react";
import Image from "next/image";

interface FileThumbProps {
  file: TelegramFile;
  className?: string;
  imageClassName?: string;
  alt?: string;
  fill?: boolean;
  priority?: boolean;
}

export function FileThumb({
  file,
  className,
  imageClassName,
  alt,
  fill = true,
  priority = false,
}: FileThumbProps) {
  const [highResLoaded, setHighResLoaded] = useState(false);
  const [highResError, setHighResError] = useState(false);
  const [showSensitive, setShowSensitive] = useState(!file.hasSensitiveContent);

  const isVideo = file.type === "video";
  const isPhoto = file.type === "photo";

  // Determine High-Res Preview URL
  let highResUrl = "";
  const isCompleted = file.downloadStatus === "completed" || !!file.localPath;
  if (isCompleted && (isPhoto || isVideo)) {
    highResUrl = `${getApiUrl()}/${file.telegramId}/file/${file.uniqueId}`;
  } else if (file.thumbnailFile?.uniqueId) {
    highResUrl = `${getApiUrl()}/${file.telegramId}/file/${file.thumbnailFile.uniqueId}`;
  }

  const hasBlurBase64 = !!file.thumbnail;
  const blurUrl = hasBlurBase64 ? `data:image/jpeg;base64,${file.thumbnail}` : "";

  return (
    <div
      className={cn(
        "relative flex h-full w-full items-center justify-center overflow-hidden bg-muted/40",
        className,
      )}
    >
      {/* 1. Low-Res Blur Placeholder (if present and high-res not yet loaded) */}
      {hasBlurBase64 && !highResLoaded && (
        <img
          src={blurUrl}
          alt={alt || file.fileName || "Thumbnail placeholder"}
          className={cn(
            "absolute inset-0 h-full w-full object-cover filter blur-[4px] scale-105 transition-opacity duration-300",
            highResLoaded ? "opacity-0" : "opacity-100",
            imageClassName,
          )}
        />
      )}

      {/* 2. High-Res Image (Original file or HD thumbnail) */}
      {highResUrl && !highResError ? (
        <Image
          src={highResUrl}
          alt={alt || file.fileName || "Media thumbnail"}
          fill={fill}
          unoptimized
          priority={priority}
          loading={priority ? "eager" : "lazy"}
          onLoad={() => setHighResLoaded(true)}
          onError={() => setHighResError(true)}
          className={cn(
            "object-cover transition-opacity duration-300",
            highResLoaded ? "opacity-100" : "opacity-0",
            imageClassName,
          )}
        />
      ) : hasBlurBase64 ? (
        // 3. Fallback to base64 if no HD URL or failed
        <img
          src={blurUrl}
          alt={alt || file.fileName || "Thumbnail"}
          className={cn(
            "h-full w-full object-cover",
            imageClassName,
          )}
        />
      ) : (
        // 4. Fallback icon
        <div className="flex flex-col items-center justify-center text-muted-foreground/50">
          {isVideo ? (
            <Film className="size-8 stroke-1" />
          ) : isPhoto ? (
            <ImageIcon className="size-8 stroke-1" />
          ) : (
            <FileText className="size-8 stroke-1" />
          )}
        </div>
      )}

      {/* Sensitive Content Overlay */}
      {file.hasSensitiveContent && !showSensitive && (
        <div
          onClick={(e) => {
            e.stopPropagation();
            setShowSensitive(true);
          }}
          className="absolute inset-0 z-10 flex cursor-pointer flex-col items-center justify-center bg-zinc-950/80 p-2 text-center text-white backdrop-blur-md transition hover:bg-zinc-950/70"
        >
          <EyeOff className="mb-1 size-5 text-amber-400" />
          <span className="text-[11px] font-medium">Sensitive Content</span>
          <span className="text-[9px] text-zinc-400">Click to reveal</span>
        </div>
      )}
    </div>
  );
}
