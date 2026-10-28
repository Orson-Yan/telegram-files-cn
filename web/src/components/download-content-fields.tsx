import type { AutoDownloadRule } from "@/lib/types";

export const standardDownloadTypes = [
  { key: "photo", label: "照片", desc: "照片 / 图片" },
  { key: "video", label: "视频", desc: "视频（含文件形式）" },
  { key: "audio", label: "音频", desc: "音乐 / 音频" },
  { key: "file", label: "文件", desc: "文档 / 压缩包等全部文件" },
] as const;

export function DownloadContentFields({
  value,
  onChange,
}: {
  value: AutoDownloadRule;
  onChange: (rule: AutoDownloadRule) => void;
}) {
  const currentTypes = value.contentTypes ?? [];

  const toggle = (key: string) => {
    const next = currentTypes.includes(key)
      ? currentTypes.filter((k) => k !== key)
      : [...currentTypes, key];
    onChange({ ...value, contentTypes: next });
  };

  return (
    <fieldset className="rounded-md border bg-muted/20 p-3">
      <div className="mb-2 flex items-center justify-between">
        <legend className="px-1 text-sm font-semibold">
          下载分类（留空默认全部文件）
        </legend>
        <div className="flex gap-2 text-xs">
          <button
            type="button"
            className="text-primary hover:underline"
            onClick={() => onChange({ ...value, contentTypes: [] })}
          >
            所有文件
          </button>
          <span className="text-muted-foreground">|</span>
          <button
            type="button"
            className="text-primary hover:underline"
            onClick={() =>
              onChange({
                ...value,
                contentTypes: ["photo", "video"],
              })
            }
          >
            仅媒体
          </button>
          <span className="text-muted-foreground">|</span>
          <button
            type="button"
            className="text-muted-foreground hover:underline"
            onClick={() => onChange({ ...value, contentTypes: [] })}
          >
            清空
          </button>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {standardDownloadTypes.map((item) => {
          const checked = currentTypes.includes(item.key);
          return (
            <label
              key={item.key}
              className={`flex cursor-pointer items-center gap-2 rounded-md border p-2.5 text-sm transition-all ${
                checked
                  ? "border-primary/50 bg-primary/10 text-primary font-medium"
                  : "border-border bg-background hover:bg-muted/50"
              }`}
            >
              <input
                type="checkbox"
                checked={checked}
                aria-label={item.label}
                onChange={() => toggle(item.key)}
                className="size-4 rounded border-gray-300 accent-primary"
              />
              <div className="flex flex-col">
                <span>{item.label}</span>
                <span className="text-xs text-muted-foreground">{item.desc}</span>
              </div>
            </label>
          );
        })}
      </div>
      <p className="mt-2 text-xs text-muted-foreground">
        标准分类：照片、视频、音频、文件。可一键选择“所有文件”或“仅媒体”，留空默认下载全部。
      </p>
    </fieldset>
  );
}
