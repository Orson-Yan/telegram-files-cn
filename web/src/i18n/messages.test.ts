import { describe, expect, it } from "vitest";
import { translateText } from "@/i18n/messages";

describe("translateText", () => {
  it("translates exact UI messages while preserving whitespace", () => {
    expect(translateText("  Download  ", "zh-CN")).toBe("  下载  ");
    expect(translateText("Media", "zh-CN")).toBe("媒体");
    expect(translateText("Photo -> Video -> Audio -> File", "zh-CN")).toBe(
      "图片 → 视频 → 音频 → 文件",
    );
  });

  it("translates dynamic counts", () => {
    expect(translateText("3 unread", "zh-CN")).toBe("3 条未读");
    expect(translateText("Download 4 selected files", "zh-CN")).toBe(
      "下载已选中的 4 个文件",
    );
    expect(translateText("12 queued", "zh-CN")).toBe("12 个待下载");
    expect(translateText("Range: 10 - 2000 MB", "zh-CN")).toBe("范围：10 - 2000 MB");
    expect(translateText("Min (MB)", "zh-CN")).toBe("最小 (MB)");
    expect(translateText("Max (GB)", "zh-CN")).toBe("最大 (GB)");
  });

  it("translates filter and column setting UI labels", () => {
    expect(translateText("Offline", "zh-CN")).toBe("离线搜索");
    expect(translateText("Show/hide columns", "zh-CN")).toBe("显示/隐藏列");
    expect(translateText("Drag and drop to reorder columns", "zh-CN")).toBe("拖拽调整列顺序");
    expect(translateText("Sent Date", "zh-CN")).toBe("发送日期");
    expect(translateText("Query Keyword", "zh-CN")).toBe("搜索关键词");
    expect(translateText("Media & File Filtering", "zh-CN")).toBe("媒体与文件过滤");
    expect(translateText("Filter Options", "zh-CN")).toBe("筛选选项");
    expect(translateText("File Size Range", "zh-CN")).toBe("文件体积范围");
    expect(translateText("Cloud archive", "zh-CN")).toBe("云端转存");
    expect(translateText("5 files selected", "zh-CN")).toBe("已选择 5 个文件");
    expect(translateText("Are you sure you want to delete the selected files?", "zh-CN")).toBe(
      "确定要删除所选文件吗？",
    );
  });

  it("does not alter English or unknown user content", () => {
    expect(translateText("Download", "en")).toBe("Download");
    expect(translateText("My family chat", "zh-CN")).toBe("My family chat");
  });
});
