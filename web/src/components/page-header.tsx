"use client";

import React, { useState } from "react";
import Link from "next/link";
import { ArrowLeft, LogOut } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import ThemeToggleButton from "@/components/theme-toggle-button";
import { LanguageToggleButton } from "@/i18n/language-toggle-button";
import { PlatformBindingShortcut } from "@/components/platform-binding-shortcut";
import { SettingsDialog } from "@/components/settings-dialog";
import { TooltipWrapper } from "@/components/ui/tooltip";
import { useAdminSession } from "@/hooks/use-admin-session";
import { cn } from "@/lib/utils";

export interface PageHeaderProps {
  title: React.ReactNode;
  icon?: React.ReactNode;
  backHref?: string;
  backLabel?: string;
  badge?: React.ReactNode;
  extraActions?: React.ReactNode;
  className?: string;
}

export function PageHeader({
  title,
  icon,
  backHref = "/",
  backLabel = "Home",
  badge,
  extraActions,
  className,
}: PageHeaderProps) {
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

  return (
    <Card className={cn("mb-6 border-border/60 shadow-sm", className)}>
      <CardContent className="p-3.5 sm:p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          {/* Left section: navigation and title */}
          <div className="flex min-w-0 flex-1 items-center gap-2.5 sm:gap-3">
            {backHref && (
              <Button
                variant="ghost"
                size="sm"
                className="h-8 shrink-0 px-2.5 text-muted-foreground hover:text-foreground"
                asChild
              >
                <Link href={backHref}>
                  <ArrowLeft className="mr-1.5 size-4" />
                  <span className="text-xs sm:text-sm">{backLabel}</span>
                </Link>
              </Button>
            )}

            <div className="flex min-w-0 items-center gap-2">
              {icon && <span className="shrink-0 text-foreground">{icon}</span>}
              <h1 className="truncate text-base font-semibold tracking-tight sm:text-lg">
                {title}
              </h1>
              {badge && <span className="shrink-0">{badge}</span>}
            </div>
          </div>

          {/* Right section: utility controls */}
          <div className="flex items-center gap-1 sm:gap-1.5">
            {extraActions}
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
      </CardContent>
    </Card>
  );
}
