"use client";

import React, { useEffect, useState } from "react";
import { AlertTriangle, RotateCcw, ArrowLeft, ChevronDown, ChevronUp, Copy, Check } from "lucide-react";
import { Button } from "@/components/ui/button";

interface ErrorProps {
  error: Error & { digest?: string };
  reset: () => void;
}

export default function GlobalError({ error, reset }: ErrorProps) {
  const [showDetails, setShowDetails] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    // Log client-side error to console
    console.error("Next.js Client Boundary Error:", error);
  }, [error]);

  const handleCopy = async () => {
    try {
      const text = `${error.name}: ${error.message}\n${error.stack || ""}\nDigest: ${error.digest || "none"}`;
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // ignore
    }
  };

  return (
    <div className="flex min-h-[70vh] w-full flex-col items-center justify-center p-6 text-center">
      <div className="flex size-16 items-center justify-center rounded-2xl bg-destructive/10 text-destructive mb-6 shadow-sm">
        <AlertTriangle className="size-8" />
      </div>

      <h2 className="text-2xl font-bold tracking-tight mb-2">
        页面加载异常 / Page Load Error
      </h2>

      <p className="max-w-md text-sm text-muted-foreground mb-6 leading-relaxed">
        渲染或加载数据时遇到了未预期的错误。您可以尝试刷新恢复，或返回上一页。
        <br />
        <span className="text-xs text-muted-foreground/80">
          An unexpected error occurred during rendering. You can try reloading or going back.
        </span>
      </p>

      <div className="flex flex-wrap items-center justify-center gap-3 mb-6">
        <Button
          onClick={() => reset()}
          className="gap-2 shadow-sm font-medium"
        >
          <RotateCcw className="size-4" />
          重新尝试 / Retry
        </Button>
        <Button
          variant="outline"
          onClick={() => {
            if (typeof window !== "undefined") {
              window.history.back();
            }
          }}
          className="gap-2 font-medium"
        >
          <ArrowLeft className="size-4" />
          返回上一页 / Go Back
        </Button>
      </div>

      {/* Expandable Debug / Stack Info */}
      <div className="w-full max-w-xl text-left">
        <button
          type="button"
          onClick={() => setShowDetails(!showDetails)}
          className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors mx-auto py-1 px-2 rounded"
        >
          <span>{showDetails ? "收起错误详情" : "展开错误详情 (Debug Info)"}</span>
          {showDetails ? <ChevronUp className="size-3" /> : <ChevronDown className="size-3" />}
        </button>

        {showDetails && (
          <div className="mt-3 rounded-lg border bg-muted/40 p-4 text-xs font-mono relative overflow-hidden">
            <div className="flex items-center justify-between border-b pb-2 mb-2 text-muted-foreground">
              <span className="font-semibold text-destructive">{error.name || "Error"}</span>
              <button
                type="button"
                onClick={handleCopy}
                className="flex items-center gap-1 hover:text-foreground text-[11px] bg-background/80 px-2 py-0.5 rounded border"
              >
                {copied ? <Check className="size-3 text-emerald-500" /> : <Copy className="size-3" />}
                {copied ? "已复制" : "复制日志"}
              </button>
            </div>
            <p className="text-foreground font-medium mb-1 break-all">{error.message}</p>
            {error.digest && (
              <p className="text-muted-foreground text-[11px] mb-2">Digest: {error.digest}</p>
            )}
            {error.stack && (
              <pre className="max-h-48 overflow-y-auto whitespace-pre-wrap text-[10px] text-muted-foreground/90 leading-normal">
                {error.stack}
              </pre>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
