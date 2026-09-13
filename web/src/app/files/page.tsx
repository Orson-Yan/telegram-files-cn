"use client";

import Files from "@/components/files";
import { PageHeader } from "@/components/page-header";
import { FolderOpen } from "lucide-react";

export default function FilesPage() {
  return (
    <div className="container mx-auto px-4 py-6">
      <PageHeader
        title="Telegram Files Manager"
        icon={<FolderOpen className="size-5 text-emerald-500" />}
      />
      <Files accountId="-1" chatId="-1" />
    </div>
  );
}
