import { describe, expect, it } from "vitest";
import { channelTaskState, downloadTemplate, type ChannelTask } from "@/components/channel-download-manager";

const task: ChannelTask = { id: "1:2", account_id: "1", chat_id: "2", title: "channel", rule_json: "{}", enabled: 1, paused: 0, initialized: 1, history_done: 0, counts: {}, bytes: {}, scanned_count: 0, last_check: 0, next_scan_at: 0 };
describe("channel downloads", () => {
  it("keeps history discovery distinct from actual file completion", () => {
    expect(channelTaskState(task)).toBe("扫描历史中");
    expect(channelTaskState({ ...task, history_done: 1, counts: { WAITING: 4 } })).toBe("历史已扫描，继续下载中");
    expect(channelTaskState({ ...task, history_done: 1, counts: { COMPLETED: 4 } })).toBe("历史已完成，持续监听中");
    expect(channelTaskState({ ...task, paused: 1 })).toBe("已暂停派发");
  });
  it("video templates configure Telegram native video message type", () => {
    const rule = downloadTemplate("video");
    expect(rule.fileTypes).toEqual(["video"]);
    expect(rule.contentTypes).toEqual([]);
    expect(rule.downloadHistory).toBe(true);
    expect(downloadTemplate("all").fileTypes).toEqual([]);
  });
  it("media templates configure photo, video, and audio message types", () => {
    const rule = downloadTemplate("media");
    expect(rule.fileTypes).toEqual(["photo", "video", "audio"]);
    expect(rule.downloadHistory).toBe(true);
  });
});
