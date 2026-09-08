"use client";

import Link from "next/link";
import { ArrowLeft, FolderSync } from "lucide-react";
import { LocalOrganizeManager } from "@/components/local-organize-manager";
import ThemeToggleButton from "@/components/theme-toggle-button";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

export default function LocalOrganizePage() {
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
              <FolderSync className="size-6" />
              <h1 className="truncate text-lg font-semibold">
                Local Organization
              </h1>
            </div>
            <ThemeToggleButton />
          </div>
        </CardContent>
      </Card>
      <LocalOrganizeManager />
    </div>
  );
}
