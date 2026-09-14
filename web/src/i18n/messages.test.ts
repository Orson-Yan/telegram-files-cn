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

  it("translates v1.0.0 gallery, sidebar and batch archive UI messages", () => {
    expect(translateText("Table View", "zh-CN")).toBe("表格视图");
    expect(translateText("Gallery View", "zh-CN")).toBe("画廊视图");
    expect(translateText("Post & Album Gallery view", "zh-CN")).toBe("时序相册画廊视图");
    expect(translateText("Downloaded on NAS", "zh-CN")).toBe("已归档到本地/NAS");
    expect(translateText("Videos", "zh-CN")).toBe("视频");
    expect(translateText("Photos", "zh-CN")).toBe("图片");
    expect(translateText("Documents", "zh-CN")).toBe("文档");
    expect(translateText("Channels & Groups", "zh-CN")).toBe("频道与群组");
    expect(translateText("Search channels...", "zh-CN")).toBe("搜索频道或群组...");
    expect(translateText("Batch archive", "zh-CN")).toBe("批量归档");
    expect(translateText("Archive to NAS / Local Storage", "zh-CN")).toBe("归档至本地 / NAS 存储");
    expect(translateText("Target directory", "zh-CN")).toBe("目标存储目录");
    expect(translateText("Start Archiving", "zh-CN")).toBe("开始归档");
    expect(translateText("Files & Downloads", "zh-CN")).toBe("文件与下载中心");
    expect(translateText("Channel automations", "zh-CN")).toBe("频道自动化流水线");
    expect(translateText("Cloud Archive", "zh-CN")).toBe("云端转发归档");
    expect(translateText("Download monitor", "zh-CN")).toBe("下载监控中心");
  });

  it("translates dynamic counts and compound messages", () => {
    expect(translateText("3 unread", "zh-CN")).toBe("3 条未读");
    expect(translateText("Download 4 selected files", "zh-CN")).toBe(
      "下载已选中的 4 个文件",
    );
    expect(translateText("12 queued", "zh-CN")).toBe("12 个待下载");
    expect(translateText("3 items", "zh-CN")).toBe("3 项");
    expect(translateText("Range: 10 - 2000 MB", "zh-CN")).toBe("范围：10 - 2000 MB");
    expect(translateText("Min (MB)", "zh-CN")).toBe("最小 (MB)");
    expect(translateText("Max (GB)", "zh-CN")).toBe("最大 (GB)");
    expect(translateText("Select group", "zh-CN")).toBe("选择该组");
    expect(translateText("Open feature", "zh-CN")).toBe("进入功能");
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
    expect(translateText("5 files selected", "zh-CN")).toBe("已选择 5 个文件");
    expect(translateText("Are you sure you want to delete the selected files?", "zh-CN")).toBe(
      "确定要删除所选文件吗？",
    );
  });

  it("supports reverse translation from Chinese to English when locale is en", () => {
    expect(translateText("表格视图", "en")).toBe("Table View");
    expect(translateText("画廊视图", "en")).toBe("Gallery View");
    expect(translateText("时序相册画廊视图", "en")).toBe("Post & Album Gallery view");
    expect(translateText("已归档到本地/NAS", "en")).toBe("Downloaded on NAS");
    expect(translateText("批量归档", "en")).toBe("Batch archive");
    expect(translateText("下载", "en")).toBe("Download");
    expect(translateText("取消", "en")).toBe("Cancel");
    expect(translateText("设置", "en")).toBe("Settings");
    expect(translateText("每日配额已更新", "en")).toBe("Daily limit updated");
    expect(translateText("暂无可选标签", "en")).toBe("No tags available");
  });

  it("does not alter English or unknown user content", () => {
    expect(translateText("Download", "en")).toBe("Download");
    expect(translateText("My family chat", "zh-CN")).toBe("My family chat");
    expect(translateText("Custom user text", "en")).toBe("Custom user text");
  });
});
