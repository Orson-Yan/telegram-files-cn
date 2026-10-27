import type { AutoDownloadRule } from "@/lib/types";

export function DownloadContentFields({ value, onChange }: { value: AutoDownloadRule; onChange: (rule: AutoDownloadRule) => void }) {
  const labels = { photo: "图片", video: "视频（含文件形式）", audio: "音频", ebook: "电子书", archive: "压缩包", file: "其他文件" };
  return <fieldset className="rounded-md border bg-muted/20 p-3">
    <legend className="px-1 text-sm font-medium">内容类别（留空为全部）</legend>
    <div className="flex flex-wrap gap-3">{Object.entries(labels).map(([key, label]) => <label key={key} className="flex items-center gap-1.5 text-sm">
      <input type="checkbox" checked={value.contentTypes?.includes(key) ?? false} onChange={() => onChange({ ...value, contentTypes: value.contentTypes?.includes(key) ? value.contentTypes.filter((item) => item !== key) : [...value.contentTypes ?? [], key] })} />{label}
    </label>)}</div>
    <p className="mt-2 text-xs text-muted-foreground">与高级设置中的消息类型、关键词和大小条件同时满足。修改后按新规则补扫历史。</p>
  </fieldset>;
}
