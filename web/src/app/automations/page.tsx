"use client";

import { Workflow } from "lucide-react";
import { AutomationOverview } from "@/components/automation-overview";
import { PageHeader } from "@/components/page-header";

export default function AutomationsPage() {
  return (
    <div className="container mx-auto px-4 py-6">
      <PageHeader
        title="Automation Overview"
        icon={<Workflow className="size-5 text-violet-500" />}
      />
      <AutomationOverview />
    </div>
  );
}
