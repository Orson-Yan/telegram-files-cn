"use client";

import { ConnectionHealthPanel } from "@/components/connection-health";
import { EmptyState } from "@/components/empty-state";
import { useTelegramAccount } from "@/hooks/use-telegram-account";

export default function Home() {
  const { getAccounts, handleAccountChange, isLoading } = useTelegramAccount();
  const accounts = getAccounts();
  return (
    <EmptyState
      connectionHealth={<ConnectionHealthPanel />}
      isLoadingAccount={isLoading}
      hasAccounts={(accounts ?? []).length > 0}
      accounts={accounts}
      onSelectAccount={handleAccountChange}
    />
  );
}
