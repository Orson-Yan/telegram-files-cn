"use client";

import { useState } from "react";
import useSWR from "swr";
import prettyBytes from "pretty-bytes";
import { useDebounce } from "use-debounce";
import { useTelegramAccount } from "@/hooks/use-telegram-account";
import { createDefaultAuto } from "@/lib/automation";
import { POST, request } from "@/lib/api";
import type { AutoDownloadRule, TelegramChat } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Download, Plus, RefreshCw, Pause, Play, Copy } from "lucide-react";

export interface ChannelTask {
  id: string; account_id: string; chat_id: string; title: string; rule_json: string;
  enabled: number; paused: number; initialized: number; history_done: number;
  counts: Record<string, number>; bytes: Record<string, number>;
  scanned_count: number; last_check: number; next_scan_at: number; last_error?: string; blocked_reason?: string;
  comment_scans?: number; comment_errors?: number; last_success_at?: number;
}
interface FileFailure { id: string; file_name?: string; status: string; attempts: number; last_error?: string; }
const types = { photo: "照片消息", video: "视频消息", audio: "音乐消息", file: "文档消息", voice: "语音", video_note: "圆形视频", animation: "GIF / 动画", sticker: "贴纸" };
const categories = { photo: "图片", video: "视频（含文件形式）", audio: "音频", ebook: "电子书", archive: "压缩包", file: "其他文件" };
const fieldClass = "rounded-md border bg-background px-3 py-2 text-sm";
function localDate(value?: number): string {
  if (!value) return "";
  const date = new Date(value * 1000);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 10);
}
export function channelTaskState(task: ChannelTask): string {
  if (!task.enabled) return "规则已关闭";
  if (task.paused) return "已暂停派发";
  if (task.blocked_reason) return task.blocked_reason;
  if (task.last_error) return "等待恢复";
  if (!task.initialized) return "准备扫描";
  if (!task.history_done) return "扫描历史中";
  if ((task.comment_scans ?? 0) > 0) return "扫描评论历史中";
  if ((task.counts.WAITING ?? 0) + (task.counts.RUNNING ?? 0) + (task.counts.STARTING ?? 0) + (task.counts.RETRY ?? 0) > 0) return "历史已扫描，继续下载中";
  if ((task.counts.FAILED ?? 0) > 0) return "存在失败文件，持续监听中";
  if ((task.counts.PAUSED ?? 0) > 0) return "有文件暂停，持续监听中";
  if ((task.comment_errors ?? 0) > 0) return "部分评论不可访问，持续监听中";
  return "历史已完成，持续监听中";
}
export function downloadTemplate(preset: string): AutoDownloadRule {
  return { ...createDefaultAuto().download.rule, fileTypes: [], contentTypes: preset === "all" ? [] : [preset] };
}

export function ChannelDownloadManager() {
  const { data: tasks, error, mutate } = useSWR<ChannelTask[]>("/channel-downloads", { refreshInterval: 5_000, refreshWhenHidden: false });
  const { getAccounts, accountId: currentAccount } = useTelegramAccount();
  const accounts = getAccounts();
  const [open, setOpen] = useState(false);
  const [account, setAccount] = useState("");
  const accountId = account || currentAccount || accounts[0]?.id || "";
  const [search, setSearch] = useState("");
  const [debouncedSearch] = useDebounce(search, 300);
  const [limit, setLimit] = useState(100);
  const [selected, setSelected] = useState<string[]>([]);
  const [links, setLinks] = useState("");
  const [rule, setRule] = useState<AutoDownloadRule>(() => downloadTemplate("all"));
  const [destination, setDestination] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [sample, setSample] = useState<{ name: string; category: string; matched: boolean }[]>([]);
  const [checked, setChecked] = useState<string[]>([]);
  const [failureSource, setFailureSource] = useState<string | null>(null);
  const { data: failures } = useSWR<FileFailure[]>(failureSource ? `/channel-downloads/${encodeURIComponent(failureSource)}/files` : null);
  const { data: chats, isLoading, error: chatError, mutate: reloadChats } = useSWR<TelegramChat[]>(
    open && accountId ? `/telegram/${accountId}/chats?scope=all&limit=${limit}&query=${encodeURIComponent(debouncedSearch)}` : null,
    { refreshInterval: 30_000, refreshWhenHidden: false },
  );
  const toggle = (values: string[], value: string) => values.includes(value) ? values.filter((item) => item !== value) : [...values, value];

  async function act(ids: string[], action: string) {
    if (ids.length === 0) return;
    setBusy(action); setNotice("");
    try {
      const result = await Promise.allSettled(ids.map((id) => POST(`/channel-downloads/${encodeURIComponent(id)}/${action}`, {})));
      const rejected = result.filter((r) => r.status === "rejected");
      if (rejected.length) setNotice(`${rejected.length} 个任务操作失败，请刷新后重试。`);
      await mutate();
    } catch (caught) { setNotice(caught instanceof Error ? caught.message : "操作失败"); }
    finally { setBusy(null); }
  }
  async function create() {
    setBusy("create"); setNotice("");
    try {
      const response = await POST("/channel-downloads", { accountId, chatIds: selected, links: links.split(/\n/).map((line) => line.trim()).filter(Boolean), rule, destination }) as { items: { created: boolean; error?: string; reference?: string }[] };
      const failed = response.items.filter((item) => !item.created);
      await mutate();
      if (failed.length) setNotice(failed.map((item) => `${item.reference ?? "频道"}：${item.error}`).join("；"));
      else { setOpen(false); setSelected([]); setLinks(""); setSample([]); }
    } catch (caught) { setNotice(caught instanceof Error ? caught.message : "创建失败"); }
    finally { setBusy(null); }
  }
  async function preview() {
    setBusy("preview"); setNotice("");
    try {
      const response = await POST("/channel-downloads/preview", { accountId, chatId: selected[0] || links.split(/\n/)[0], rule }) as { items: typeof sample };
      setSample(response.items);
    } catch (caught) { setNotice(caught instanceof Error ? caught.message : "预览失败"); }
    finally { setBusy(null); }
  }
  async function manifest(id: string) {
    setBusy("manifest");
    try {
      const data: unknown = await request(`/channel-downloads/${encodeURIComponent(id)}/manifest`);
      const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
      const link = document.createElement("a"); link.href = url; link.download = "channel-downloads.json"; link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (caught) { setNotice(caught instanceof Error ? caught.message : "导出失败"); }
    finally { setBusy(null); }
  }

  return <Card className="mb-6">
    <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-3">
      <div><CardTitle className="flex items-center gap-2"><Download className="size-5" />频道下载任务</CardTitle>
        <p className="mt-2 text-sm text-muted-foreground">历史补齐与新增监听同时进行，重启后继续。</p></div>
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" size="sm" onClick={() => void mutate()}><RefreshCw className="mr-1 size-4" />刷新</Button>
        <Button size="sm" onClick={() => { setNotice(""); setOpen(true); }}><Plus className="mr-1 size-4" />新建频道任务</Button>
      </div>
    </CardHeader>
    <CardContent>
      {error ? <p role="alert" className="mb-3 text-sm text-destructive">频道任务加载失败，请刷新重试。</p> : null}
      {notice && !open ? <p role="alert" className="mb-3 text-sm text-destructive">{notice}</p> : null}
      {checked.length > 0 ? <div className="mb-4 flex flex-wrap items-center gap-2 text-sm">
        <span>已选 {checked.length} 个任务</span>
        {([['pause', '批量暂停'], ['resume', '批量继续'], ['retry', '重试失败']] as const).map(([action, text]) =>
          <Button key={action} size="sm" variant="outline" disabled={!!busy} onClick={() => void act(checked, action)}>{text}</Button>)}
      </div> : null}
      {tasks?.length === 0 ? <p className="py-6 text-center text-sm text-muted-foreground">还没有频道下载任务。选择频道后即可下载全部历史并持续接收新增文件。</p> : null}
      <div className="grid gap-3 xl:grid-cols-2">
        {tasks?.map((task) => {
          const c = task.counts;
          const discovered = Object.values(c).reduce((sum, value) => sum + value, 0);
          return <article key={task.id} className="min-w-0 rounded-lg border p-4">
            <div className="flex items-start gap-3">
              <input type="checkbox" aria-label={`选择任务 ${task.title}`} checked={checked.includes(task.id)} onChange={() => setChecked(toggle(checked, task.id))} className="mt-1" />
              <div className="min-w-0 flex-1"><p className="truncate font-medium">{task.title}</p>
                <p className="break-all text-xs text-muted-foreground">账号 {task.account_id} · 频道 {task.chat_id}</p></div>
              <Badge variant="secondary" className="max-w-48 whitespace-normal">{channelTaskState(task)}</Badge>
            </div>
            <div className="my-3 flex flex-wrap gap-x-4 gap-y-1 text-sm">
              <span>已发现 {discovered}</span><span>等待 {c.WAITING ?? 0}</span>
              <span>运行 {(c.RUNNING ?? 0) + (c.STARTING ?? 0)}</span><span>完成 {c.COMPLETED ?? 0}</span>
              <span>失败 {c.FAILED ?? 0}</span><span>待重试 {c.RETRY ?? 0}</span><span>文件暂停 {c.PAUSED ?? 0}</span>
              <span>完成体积 {prettyBytes(task.bytes.COMPLETED ?? 0)}</span>
              {task.comment_errors ? <span>受限评论区 {task.comment_errors}</span> : null}
            </div>
            <p className="mb-3 text-xs text-muted-foreground">已扫描 {task.scanned_count} 条消息 · {task.last_check ? `最近检查 ${new Date(task.last_check).toLocaleString()}` : "等待首次检查"}</p>
            {task.last_success_at ? <p className="mb-3 text-xs text-muted-foreground">最近下载成功 {new Date(task.last_success_at).toLocaleString()}</p> : null}
            {task.last_error ? <p className="mb-3 break-words text-sm text-destructive">{task.last_error}{task.next_scan_at > Date.now() ? ` · ${new Date(task.next_scan_at).toLocaleTimeString()} 后重试` : ""}</p> : null}
            <div className="flex flex-wrap gap-2">
              <Button size="sm" variant="outline" disabled={!!busy} onClick={() => void act([task.id], task.paused || !task.enabled ? "resume" : "pause")}>
                {task.paused || !task.enabled ? <Play className="mr-1 size-3" /> : <Pause className="mr-1 size-3" />}{task.paused || !task.enabled ? "继续" : "暂停派发"}</Button>
              <Button size="sm" variant="outline" disabled={!!busy} onClick={() => void act([task.id], "retry")}>重试失败</Button>
              <Button size="sm" variant="outline" disabled={!!busy} onClick={() => void act([task.id], "rescan")}>补扫历史</Button>
              <Button size="sm" variant="ghost" onClick={() => setFailureSource(failureSource === task.id ? null : task.id)}>失败明细</Button>
              <Button size="sm" variant="ghost" disabled={!!busy} onClick={() => void manifest(task.id)}>导出清单</Button>
              <Button size="sm" variant="ghost" onClick={() => { setAccount(task.account_id); setRule({ ...downloadTemplate("all"), ...JSON.parse(task.rule_json) }); setSelected([task.chat_id]); setLinks(""); setDestination(""); setSample([]); setNotice(""); setOpen(true); }}>编辑规则</Button>
              <Button size="sm" variant="ghost" onClick={() => { setAccount(task.account_id); setRule({ ...downloadTemplate("all"), ...JSON.parse(task.rule_json) }); setSelected([]); setLinks(""); setDestination(""); setSample([]); setNotice(""); setOpen(true); }}><Copy className="mr-1 size-3" />复制规则</Button>
            </div>
            {failureSource === task.id ? <div className="mt-3 space-y-2 border-t pt-3 text-xs">
              {failures?.length === 0 ? <p>没有失败或等待重试的文件。</p> : failures?.map((file) => <p key={file.id} className="break-words">{file.file_name || file.id} · 尝试 {file.attempts} 次 · {file.last_error}</p>)}
            </div> : null}
          </article>;
        })}
      </div>
    </CardContent>

    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-3xl">
        <DialogHeader><DialogTitle>新建频道下载任务</DialogTitle>
          <DialogDescription>选择频道和下载条件。同一账号和频道复用已有任务；修改规则后按新条件补扫历史，已下载文件会复用。</DialogDescription></DialogHeader>
        <div className="grid gap-4 md:grid-cols-2">
          <label className="grid gap-1 text-sm">Telegram 账号<select aria-label="下载账号" className={fieldClass} value={accountId} onChange={(event) => { setAccount(event.target.value); setSelected([]); setLimit(100); }}>
            <option value="" disabled>请选择账号</option>{accounts.map((item) => <option key={item.id} value={item.id}>{item.name || item.id}（{item.id}）</option>)}</select></label>
          <label className="grid gap-1 text-sm">规则模板<select aria-label="规则模板" className={fieldClass} onChange={(event) => setRule(downloadTemplate(event.target.value))} defaultValue="all">
            <option value="all">所有文件</option>{Object.entries(categories).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        </div>
        <div className="rounded-lg border p-3">
          <div className="mb-2 flex gap-2"><Input aria-label="搜索下载频道" placeholder="搜索频道名称或 ID（含归档）" value={search} onChange={(event) => { setSearch(event.target.value); setLimit(100); }} />
            <Button variant="outline" onClick={() => void reloadChats(request<TelegramChat[]>(`/telegram/${accountId}/chats?scope=all&limit=${limit}&query=${encodeURIComponent(debouncedSearch)}&refresh=true`), { revalidate: false }).catch(() => undefined)} aria-label="刷新下载频道"><RefreshCw className="size-4" /></Button></div>
          {chatError ? <p role="alert" className="text-sm text-destructive">频道加载失败，请重试。</p> : null}
          <div className="grid max-h-48 gap-1 overflow-y-auto md:grid-cols-2">
            {isLoading ? <p className="p-2 text-sm">正在加载频道…</p> : chats?.map((chat) => <label key={chat.id} className="flex min-w-0 items-center gap-2 rounded p-2 text-sm hover:bg-muted">
              <input type="checkbox" checked={selected.includes(chat.id)} onChange={() => setSelected(toggle(selected, chat.id))} />
              <span className="truncate">{chat.name || chat.id}</span></label>)}
          </div>
          {chats?.length === limit && limit < 5000 ? <Button variant="ghost" size="sm" onClick={() => setLimit(limit + 100)}>加载更多频道</Button> : null}
          <p className="mt-2 text-xs text-muted-foreground">已选择 {selected.length} 个频道，每次最多创建 50 个任务。</p>
        </div>
        <label className="grid gap-1 text-sm">也可批量粘贴频道或消息链接，每行一个<textarea className={`${fieldClass} min-h-16`} value={links} onChange={(event) => setLinks(event.target.value)} placeholder="https://t.me/channel_name" /></label>
        <div className="rounded-lg border p-3 text-sm"><label className="flex items-center gap-2"><input type="checkbox" checked={rule.downloadHistory} onChange={(event) => setRule({ ...rule, downloadHistory: event.target.checked })} />下载全部历史，并持续下载新增文件</label>
          <p className="mt-1 text-xs text-muted-foreground">关闭后仅从创建任务时开始接收新增文件。暂停派发允许已运行的文件继续完成。</p></div>
        <fieldset className="rounded-lg border p-3"><legend className="px-1 text-sm font-medium">内容类别（留空为全部）</legend>
          <div className="flex flex-wrap gap-3">{Object.entries(categories).map(([value, label]) => <label key={value} className="flex items-center gap-1.5 text-sm"><input type="checkbox" checked={rule.contentTypes?.includes(value) ?? false} onChange={() => setRule({ ...rule, contentTypes: toggle(rule.contentTypes ?? [], value) })} />{label}</label>)}</div>
        </fieldset>
        <label className="grid gap-1 text-sm">保存目录（可选，留空沿用原下载位置）<Input placeholder="/nas/media/telegram" value={destination} onChange={(event) => setDestination(event.target.value)} />
          {destination ? <span className="text-xs text-muted-foreground">下载后按频道整理到 {destination}/频道目录，同名文件自动避让。</span> : null}</label>
        <details className="rounded-lg border p-3"><summary className="cursor-pointer text-sm font-medium">高级筛选</summary>
          <div className="mt-3 grid gap-3 md:grid-cols-2">
            <label className="grid gap-1 text-sm">关键词（文件名或说明）<Input value={rule.query} onChange={(event) => setRule({ ...rule, query: event.target.value })} /></label>
            <label className="grid gap-1 text-sm">允许扩展名<Input placeholder="mp4,mkv,pdf" value={rule.allowedExtensions ?? ""} onChange={(event) => setRule({ ...rule, allowedExtensions: event.target.value })} /></label>
            <label className="grid gap-1 text-sm">排除扩展名<Input placeholder="exe,apk" value={rule.deniedExtensions ?? ""} onChange={(event) => setRule({ ...rule, deniedExtensions: event.target.value })} /></label>
            {([['minSize', '最小体积（MB）'], ['maxSize', '最大体积（MB）']] as const).map(([key, label]) => <label key={key} className="grid gap-1 text-sm">{label}<Input type="number" min="0" value={rule[key] ? rule[key]! / 1048576 : ""} onChange={(event) => setRule({ ...rule, [key]: Math.max(0, Number(event.target.value)) * 1048576 })} /></label>)}
            {([['fromDate', '消息起始日期'], ['toDate', '消息结束日期']] as const).map(([key, label]) => <label key={key} className="grid gap-1 text-sm">{label}<Input type="date" value={localDate(rule[key])} onChange={(event) => setRule({ ...rule, [key]: event.target.value ? Math.floor(new Date(`${event.target.value}T${key === 'toDate' ? '23:59:59' : '00:00:00'}`).getTime() / 1000) : 0 })} /></label>)}
            <label className="grid gap-1 text-sm md:col-span-2">筛选表达式<Input value={rule.filterExpr} onChange={(event) => setRule({ ...rule, filterExpr: event.target.value })} /></label>
          </div>
          <fieldset className="mt-3"><legend className="mb-2 text-sm">Telegram 消息类型（与内容类别同时满足；留空为全部）</legend><div className="flex flex-wrap gap-3">{Object.entries(types).map(([value, label]) => <label key={value} className="flex items-center gap-1.5 text-sm"><input type="checkbox" checked={rule.fileTypes?.includes(value as typeof rule.fileTypes[number]) ?? false} onChange={() => setRule({ ...rule, fileTypes: toggle(rule.fileTypes?.filter((type) => String(type) !== "all") ?? [], value) as typeof rule.fileTypes })} />{label}</label>)}</div></fieldset>
          <label className="mt-3 flex items-center gap-2 text-sm"><input type="checkbox" checked={rule.downloadCommentFiles} onChange={(event) => setRule({ ...rule, downloadCommentFiles: event.target.checked })} />同时下载讨论区评论文件</label>
        </details>
        {sample.length > 0 ? <div className="max-h-40 overflow-auto rounded border p-3 text-xs">{sample.map((item, index) => <p key={index} className="mb-1 break-words">{item.matched ? "✓ 符合规则" : "× 不符合当前筛选"} · {item.name || item.category}</p>)}<p className="mt-2 text-muted-foreground">仅抽样最近消息，不代表全部历史数量。</p></div> : null}
        {notice ? <p role="alert" className="break-words text-sm text-destructive">{notice}</p> : null}
        <div className="flex justify-end gap-2"><Button variant="outline" disabled={!!busy || !accountId || (!selected.length && !links.trim())} onClick={() => void preview()}>预览匹配样本</Button>
          <Button disabled={!!busy || !accountId || (!selected.length && !links.trim()) || selected.length + links.split(/\n/).filter((line) => line.trim()).length > 50} onClick={() => void create()}>{busy === "create" ? "正在创建…" : "创建并开始下载"}</Button></div>
      </DialogContent>
    </Dialog>
  </Card>;
}
