"use client";

import { Button } from "@/components/ui/button";
import { useOptionalLanguage } from "@/i18n/language-provider";
import type { CloudArchiveRuleOverview } from "@/lib/types";

export function TaskStatistics({ task, activity, onIssues }: { task: CloudArchiveRuleOverview; activity: string; onIssues: () => void }) {
  const t = useOptionalLanguage()?.t ?? ((text: string) => text);
  const queue = task.queue;
  const issues = (queue?.failed || 0) + (queue?.unconfirmed || 0);
  const count = (value?: number) => value == null ? "—" : value.toLocaleString();
  return <div className="space-y-2 text-xs">
    <div className="text-sm text-foreground">{t("Current activity")}: {t(activity)}</div>
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 tabular-nums">
      <span>{t("Today's successes")} <strong className="text-foreground">{count(queue?.todayCompleted)}</strong></span>
      <span>{t("Cumulative successes")} <strong className="text-foreground">{count(queue?.completed)}</strong></span>
      <span>{t("Queued")} <strong className="text-foreground">{count(queue?.pending)}</strong></span>
      <span className={issues ? "text-destructive" : undefined}>{t("Issues")} <strong>{issues}</strong></span>
      {issues > 0 && <Button size="sm" variant="outline" className="h-7 px-2 text-xs text-destructive" onClick={onIssues}>{t("Handle issues")} {issues}</Button>}
    </div>
  </div>;
}
