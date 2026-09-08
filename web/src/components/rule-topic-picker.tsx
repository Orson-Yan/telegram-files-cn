"use client";

import { useState } from "react";
import useSWR from "swr";
import { useDebounce } from "use-debounce";
import { Check, ChevronsUpDown, Loader2 } from "lucide-react";
import type { TelegramTopic } from "@/lib/types";
import { Button } from "@/components/ui/button";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";

export function RuleTopicPicker({
  accountId,
  chatId,
  value,
  onChange,
  allowAll = false,
  allowClosed = false,
}: {
  accountId?: string;
  chatId?: string;
  value?: string;
  onChange: (topicId: string) => void;
  allowAll?: boolean;
  allowClosed?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [debouncedQuery] = useDebounce(query, 350);
  const {
    data: topics,
    error,
    isLoading,
    isValidating,
  } = useSWR<TelegramTopic[]>(
    accountId && chatId
      ? `/telegram/${accountId}/chat/${chatId}/topics?query=${encodeURIComponent(debouncedQuery)}`
      : undefined,
  );
  const selected = topics?.find((topic) => topic.id === value);

  if (error) {
    return (
      <Button
        variant="outline"
        className="w-full justify-start font-normal text-destructive"
        disabled
      >
        Unable to load topics
      </Button>
    );
  }
  if (!isLoading && (topics?.length ?? 0) === 0 && !value) return null;

  return (
    <Popover open={open} onOpenChange={setOpen} modal>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          role="combobox"
          className="w-full justify-between font-normal"
          disabled={!accountId || !chatId}
        >
          <span className="truncate">
            {selected?.name ||
              (allowAll && (!value || value === "0")
                ? "All topics"
                : value || "Select topic...")}
          </span>
          {isValidating ? (
            <Loader2 className="ml-2 size-4 animate-spin opacity-50" />
          ) : (
            <ChevronsUpDown className="ml-2 size-4 opacity-50" />
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        className="w-[var(--radix-popover-trigger-width)] min-w-[260px] p-0"
      >
        <Command shouldFilter={false}>
          <CommandInput
            value={query}
            onValueChange={setQuery}
            placeholder="Search topics..."
          />
          <CommandList className="max-h-64">
            <CommandEmpty>
              {isLoading ? "Loading topics..." : "No topic found."}
            </CommandEmpty>
            <CommandGroup>
              {allowAll && (
                <CommandItem
                  value="0"
                  onSelect={() => {
                    onChange("0");
                    setOpen(false);
                  }}
                >
                  <span className="flex-1">All topics</span>
                  <Check
                    className={cn(
                      "size-4",
                      !value || value === "0"
                        ? "opacity-100"
                        : "opacity-0",
                    )}
                  />
                </CommandItem>
              )}
              {(topics ?? []).map((topic) => (
                <CommandItem
                  key={topic.id}
                  value={topic.id}
                  disabled={topic.closed && !allowClosed}
                  onSelect={() => {
                    onChange(topic.id);
                    setOpen(false);
                    setQuery("");
                  }}
                >
                  <span className="min-w-0 flex-1 truncate" translate="no">
                    {topic.name}
                  </span>
                  <span className="text-xs text-muted-foreground">
                    {topic.general
                      ? "General"
                      : topic.closed
                        ? "Closed"
                        : `#${topic.id}`}
                  </span>
                  <Check
                    className={cn(
                      "size-4",
                      value === topic.id ? "opacity-100" : "opacity-0",
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
