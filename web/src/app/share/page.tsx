"use client";

import { Network } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { PlatformBinding } from "@/components/platform-binding";
import { PublishedResources } from "@/components/published-resources";
import { SharePublicationRules } from "@/components/share-publication-rules";

export default function SharePage() {
  return (
    <main className="container mx-auto flex flex-col gap-6 px-4 py-6">
      <PageHeader
        title="Platform node binding"
        icon={<Network className="size-5 text-indigo-500" />}
      />

      <section className="flex flex-col gap-2">
        <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
          <Network className="size-4" aria-hidden="true" />
          Telegram Seed control plane
        </div>
        <p className="max-w-2xl text-sm text-muted-foreground">
          Give this installation an independent identity for heartbeats and
          future share tasks.
        </p>
      </section>

      <PlatformBinding />
      <SharePublicationRules />
      <PublishedResources />
    </main>
  );
}
