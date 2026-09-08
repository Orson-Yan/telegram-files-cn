"use client";

import Link from "next/link";
import { ArrowLeft, CloudUpload } from "lucide-react";
import { CloudArchiveManager } from "@/components/cloud-archive-manager";
import ThemeToggleButton from "@/components/theme-toggle-button";
import { PlatformTelegramIcon } from "@/components/platform-telegram-icon";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

export default function CloudArchivePage() {
  return (
    <div className="container mx-auto px-4 py-6">
      <Card className="mb-6">
        <CardContent className="p-4">
          <div className="flex items-center justify-between gap-4">
            <Button variant="ghost" size="sm" asChild>
              <Link href="/">
                <ArrowLeft data-icon="inline-start" /> Home
              </Link>
            </Button>
            <div className="flex min-w-0 items-center gap-2">
              <PlatformTelegramIcon className="size-6" />
              <CloudUpload className="size-5" />
              <h1 className="truncate text-lg font-semibold">
                Telegram Cloud Archive
              </h1>
            </div>
            <ThemeToggleButton />
          </div>
        </CardContent>
      </Card>
      <CloudArchiveManager />
    </div>
  );
}
