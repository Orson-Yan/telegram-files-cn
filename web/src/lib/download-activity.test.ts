import { describe, expect, it } from "vitest";
import {
  downloadedTrafficDelta,
  EMPTY_DOWNLOAD_ACTIVITY,
  normalizeDownloadOverview,
  updateDownloadActivity,
} from "@/lib/download-activity";

describe("download activity", () => {
  it("calculates aggregate speed from comparable snapshots", () => {
    const first = updateDownloadActivity(
      EMPTY_DOWNLOAD_ACTIVITY,
      { totalSize: 10_000, totalCount: 2, downloadedSize: 1_000 },
      1_000,
    );
    const second = updateDownloadActivity(
      first,
      { totalSize: 10_000, totalCount: 2, downloadedSize: 3_000 },
      3_000,
    );

    expect(first.speed).toBe(0);
    expect(second.speed).toBe(1_000);
    expect(second.lastProgressAt).toBe(3_000);
  });

  it("clears current activity without losing session traffic", () => {
    const previous = {
      ...EMPTY_DOWNLOAD_ACTIVITY,
      speed: 500,
      sessionDownloadedBytes: 4_096,
      totalCount: 1,
      totalSize: 8_192,
      downloadedSize: 4_096,
    };

    const result = updateDownloadActivity(
      previous,
      { totalSize: 0, totalCount: 0, downloadedSize: 0 },
      5_000,
    );

    expect(result.speed).toBe(0);
    expect(result.totalCount).toBe(0);
    expect(result.sessionDownloadedBytes).toBe(4_096);
  });

  it("counts only positive per-file traffic deltas", () => {
    expect(downloadedTrafficDelta(undefined, 100)).toBe(0);
    expect(downloadedTrafficDelta(100, 150)).toBe(50);
    expect(downloadedTrafficDelta(150, 20)).toBe(0);
  });

  it("normalizes legacy file-count responses for rolling upgrades", () => {
    expect(
      normalizeDownloadOverview({
        downloading: 1,
        completed: 10,
        downloadedSize: 1_024,
      }),
    ).toEqual({
      downloading: 1,
      completed: 10,
      queued: 0,
      downloadedSize: 1_024,
      queuedSize: 0,
      activeSize: 0,
      downloadLimit: 5,
    });
  });
});
