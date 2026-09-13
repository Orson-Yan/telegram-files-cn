"use client";

import { FolderSync } from "lucide-react";
import { LocalOrganizeManager } from "@/components/local-organize-manager";
import { PageHeader } from "@/components/page-header";

export default function LocalOrganizePage() {
  return (
    <div className="container mx-auto px-4 py-6">
      <PageHeader
        title="Local Organization"
        icon={<FolderSync className="size-5 text-amber-500" />}
      />
      <LocalOrganizeManager />
    </div>
  );
}
