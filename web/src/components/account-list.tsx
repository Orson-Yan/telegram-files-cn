import { type TelegramAccount } from "@/lib/types";
import { Card, CardContent } from "@/components/ui/card";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import {
  ArrowRight,
  Circle,
  Network,
  PhoneCall,
  Plus,
} from "lucide-react";
import { Spoiler } from "spoiled";
import AccountDeleteDialog from "@/components/account-delete-dialog";
import { AccountDialog } from "@/components/account-dialog";
import { Button } from "@/components/ui/button";

interface AccountListProps {
  accounts: TelegramAccount[];
  onSelectAccount: (accountId: string) => void;
  showAddCard?: boolean;
}

export function AccountList({
  accounts,
  onSelectAccount,
  showAddCard = true,
}: AccountListProps) {
  return (
    <div className="mx-auto grid max-w-5xl grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {accounts.map((account) => {
        const isActive = account.status === "active" && !account.sleeping;
        const isSleeping = account.sleeping;

        return (
          <Card
            key={account.id}
            className="group relative flex cursor-pointer flex-col justify-between border-border/70 transition-all duration-200 hover:-translate-y-0.5 hover:border-primary/50 hover:shadow-md"
            onClick={(_e) => {
              onSelectAccount(account.id);
            }}
          >
            {/* Delete button: accessible on mobile by default, subtle hover on desktop */}
            <div className="absolute right-2 top-2 z-10 opacity-70 transition-opacity md:opacity-0 md:group-hover:opacity-100">
              <AccountDeleteDialog telegramId={account.id} />
            </div>

            <CardContent className="flex flex-col gap-4 p-5">
              <div className="flex items-start gap-3.5 pr-8">
                <div className="relative">
                  <Avatar className="h-12 w-12 border border-border/80 shadow-sm">
                    <AvatarImage
                      src={`data:image/jpeg;base64,${account.avatar}`}
                      alt={account.name}
                    />
                    <AvatarFallback className="font-semibold text-foreground">
                      {account.name[0]?.toUpperCase()}
                    </AvatarFallback>
                  </Avatar>
                  <span
                    className={`absolute -bottom-0.5 -right-0.5 size-3 rounded-full ring-2 ring-background ${
                      isSleeping
                        ? "bg-amber-500"
                        : isActive
                          ? "bg-emerald-500"
                          : "bg-gray-400"
                    }`}
                  />
                </div>

                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-1.5">
                    <h3 className="truncate font-semibold text-foreground">
                      {account.name}
                    </h3>
                  </div>

                  {account.status === "active" && !account.sleeping && (
                    <div className="mt-1 flex items-center text-xs text-muted-foreground">
                      <PhoneCall className="mr-1 size-3 shrink-0" />
                      <Spoiler>{account.phoneNumber}</Spoiler>
                    </div>
                  )}

                  {account.status === "inactive" && (
                    <div
                      className="mt-2"
                      onClick={(e) => e.stopPropagation()}
                    >
                      <AccountDialog>
                        <Button variant="outline" size="sm" className="h-7 text-xs">
                          Activate
                        </Button>
                      </AccountDialog>
                    </div>
                  )}
                </div>
              </div>

              {/* Status and Proxy Meta info */}
              <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border/50 pt-3 text-xs">
                <div className="flex items-center gap-1.5">
                  <Badge
                    variant={isActive ? "default" : "secondary"}
                    className="h-5 px-1.5 text-[11px] font-normal"
                  >
                    <Circle
                      className={`mr-1 size-1.5 fill-current ${
                        isSleeping
                          ? "text-amber-500"
                          : isActive
                            ? "text-emerald-400"
                            : "text-gray-400"
                      }`}
                    />
                    {isSleeping ? "Sleeping" : account.status}
                  </Badge>

                  {account.proxy && (
                    <Badge
                      variant="outline"
                      className="h-5 gap-1 px-1.5 text-[11px] font-normal text-muted-foreground"
                    >
                      <Network className="size-2.5 text-blue-500" />
                      <span className="max-w-[90px] truncate">{account.proxy}</span>
                    </Badge>
                  )}
                </div>

                <span className="flex items-center text-xs font-medium text-primary opacity-0 transition-opacity duration-200 group-hover:opacity-100">
                  Open
                  <ArrowRight className="ml-1 size-3 transition-transform duration-200 group-hover:translate-x-0.5" />
                </span>
              </div>
            </CardContent>
          </Card>
        );
      })}

      {/* Add Account Card inside the grid */}
      {showAddCard && (
        <AccountDialog isAdd={true}>
          <div className="group flex min-h-[140px] cursor-pointer flex-col items-center justify-center rounded-xl border border-dashed border-border/80 p-6 text-center transition-all duration-200 hover:border-primary/60 hover:bg-muted/40 sm:min-h-[150px]">
            <div className="mb-2 flex size-10 items-center justify-center rounded-full bg-muted text-muted-foreground transition-colors group-hover:bg-primary/10 group-hover:text-primary">
              <Plus className="size-5 transition-transform duration-200 group-hover:scale-110" />
            </div>
            <p className="text-sm font-medium text-foreground">Add Account</p>
            <p className="mt-0.5 text-xs text-muted-foreground">
              Connect another Telegram account
            </p>
          </div>
        </AccountDialog>
      )}
    </div>
  );
}
