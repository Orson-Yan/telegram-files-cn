"use client";

import { Languages } from "lucide-react";
import { Button } from "@/components/ui/button";
import { TooltipWrapper } from "@/components/ui/tooltip";
import { useOptionalLanguage } from "@/i18n/language-provider";

export function LanguageToggleButton() {
  const language = useOptionalLanguage();
  if (!language) {
    return null;
  }

  const { locale, toggleLocale } = language;
  const switchLabel =
    locale === "zh-CN" ? "Switch to English" : "切换为简体中文";

  return (
    <TooltipWrapper content={switchLabel}>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className="size-9 gap-0.5 px-0 text-xs"
        onClick={toggleLocale}
        aria-label={switchLabel}
        data-i18n-skip
      >
        <Languages className="h-4 w-4" aria-hidden="true" />
        <span>{locale === "zh-CN" ? "EN" : "中"}</span>
      </Button>
    </TooltipWrapper>
  );
}
