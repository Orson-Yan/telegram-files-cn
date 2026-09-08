"use client";

import { useState } from "react";
import useSWR from "swr";
import type { TelegramChat } from "@/lib/types";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

export function RuleChatPicker({
  accountId,
  value,
  onChange,
  placeholder = "Select chat ...",
  excludeChatId,
}: {
  accountId?: string;
  value?: string;
  onChange: (chatId: string) => void;
  placeholder?: string;
  excludeChatId?: string;
}) {
  const [query, setQuery] = useState("");
  const { data: chats, isLoading } = useSWR<TelegramChat[]>(
    accountId
      ? `/telegram/${accountId}/chats?query=${encodeURIComponent(query)}&archived=false&chatId=${value ?? ""}`
      : undefined,
  );

  return (
    <div className="space-y-2">
      <Input
        value={query}
        disabled={!accountId}
        placeholder="Search chats..."
        onChange={(event) => setQuery(event.target.value)}
      />
      <Select value={value || undefined} onValueChange={onChange}>
        <SelectTrigger disabled={!accountId || isLoading}>
          <SelectValue
            placeholder={isLoading ? "Loading chats..." : placeholder}
          />
        </SelectTrigger>
        <SelectContent>
          {(chats ?? [])
            .filter((chat) => chat.id !== excludeChatId)
            .map((chat) => (
              <SelectItem key={chat.id} value={chat.id}>
                <span translate="no">{chat.name || chat.id}</span>
                <span className="ml-2 text-xs text-muted-foreground">
                  {chat.type}
                </span>
              </SelectItem>
            ))}
        </SelectContent>
      </Select>
    </div>
  );
}
