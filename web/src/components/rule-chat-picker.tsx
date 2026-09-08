"use client";

import { useMemo, useState } from "react";
import useSWR from "swr";
import { useDebounce } from "use-debounce";
import { Check, ChevronsUpDown, Loader2 } from "lucide-react";
import type { TelegramChat } from "@/lib/types";
import { Button } from "@/components/ui/button";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { cn } from "@/lib/utils";

type LocalChat = TelegramChat & {
  downloadedCount?: number;
  eligibleCount?: number;
  organizedCount?: number;
  downloadedSize?: number;
};

export function RuleChatPicker({
  accountId,
  value,
  onChange,
  placeholder = "Select chat ...",
  excludeChatId,
  source = "telegram",
  eligibleOnly = true,
}: {
  accountId?: string;
  value?: string;
  onChange: (chatId: string) => void;
  placeholder?: string;
  excludeChatId?: string;
  source?: "telegram" | "local";
  eligibleOnly?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [debouncedQuery] = useDebounce(query, 350);
  const endpoint = accountId
    ? source === "local"
      ? `/local-organize/sources?telegramId=${accountId}&eligibleOnly=${eligibleOnly}&query=${encodeURIComponent(debouncedQuery)}`
      : `/telegram/${accountId}/chats?query=${encodeURIComponent(debouncedQuery)}&archived=false&chatId=${value ?? ""}`
    : undefined;
  const { data: chats, isLoading, isValidating } = useSWR<LocalChat[]>(endpoint);
  const options = useMemo(
    () => (chats ?? []).filter((chat) => chat.id !== excludeChatId),
    [chats, excludeChatId],
  );
  const selected = (chats ?? []).find((chat) => chat.id === value);

  return (
    <Popover open={open} onOpenChange={setOpen} modal>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          role="combobox"
          aria-expanded={open}
          disabled={!accountId}
          className="w-full justify-between font-normal"
        >
          <span className={cn("truncate", !selected && "text-muted-foreground")}>
            {selected?.name || selected?.id || value || placeholder}
          </span>
          {isValidating ? (
            <Loader2 className="ml-2 size-4 shrink-0 animate-spin opacity-50" />
          ) : (
            <ChevronsUpDown className="ml-2 size-4 shrink-0 opacity-50" />
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        className="w-[var(--radix-popover-trigger-width)] min-w-[280px] p-0"
      >
        <Command shouldFilter={false}>
          <CommandInput
            value={query}
            onValueChange={setQuery}
            placeholder="Search by chat name or ID..."
          />
          <CommandList className="max-h-72">
            <CommandEmpty>
              {isLoading ? "Loading chats..." : "No chat found."}
            </CommandEmpty>
            <CommandGroup>
              {options.map((chat) => (
                <CommandItem
                  key={chat.id}
                  value={chat.id}
                  onSelect={() => {
                    onChange(chat.id);
                    setOpen(false);
                    setQuery("");
                  }}
                  className="gap-2"
                >
                  <div className="min-w-0 flex-1">
                    <div className="truncate" translate="no">
                      {chat.name || chat.id}
                    </div>
                    <div className="truncate text-xs text-muted-foreground">
                      {chat.type}
                      {source === "local" && chat.eligibleCount !== undefined
                        ? ` · ${chat.eligibleCount} ready · ${chat.downloadedCount ?? 0} local`
                        : ` · ${chat.id}`}
                    </div>
                  </div>
                  <Check
                    className={cn(
                      "size-4 shrink-0",
                      value === chat.id ? "opacity-100" : "opacity-0",
                    )}
                  />
                </CommandItem>
              ))}
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}
