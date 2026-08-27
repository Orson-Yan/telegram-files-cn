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
  });

  it("does not alter English or unknown user content", () => {
    expect(translateText("Download", "en")).toBe("Download");
    expect(translateText("My family chat", "zh-CN")).toBe("My family chat");
  });
});
