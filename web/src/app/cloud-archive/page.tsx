"use client";

import { CloudUpload } from "lucide-react";
import { CloudArchiveManager } from "@/components/cloud-archive-manager";
import { PageHeader } from "@/components/page-header";

export default function CloudArchivePage() {
  return (
    <div className="container mx-auto px-4 py-6">
      <PageHeader
        title="Telegram Cloud Archive"
        icon={<CloudUpload className="size-5 text-sky-500" />}
      />
      <CloudArchiveManager />
    </div>
  );
}
