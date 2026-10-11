import { describe, expect, it } from "vitest";
import {
  archiveConfigs,
  createDefaultAuto,
  hasArchiveEnabled,
  normalizeAuto,
  validateArchiveDestination,
} from "./automation";

function config(targetChatId: number, enabled = true) {
  return {
    enabled,
    rule: { ...createDefaultAuto().archive.rule, targetChatId, strictOrder: true },
  };
}

describe("multiple cloud archive destinations", () => {
  it("retains both destinations and download settings when normalizing a saved source", () => {
    const original = createDefaultAuto();
    original.archive = config(200);
    original.download.enabled = true;
    original.download.rule.query = "keep this";
    original.archiveRules = [config(300)];
    const updated = normalizeAuto(original);

    expect(archiveConfigs(updated).map((item) => item.rule.targetChatId)).toEqual([200, 300]);
    expect(updated.download).toEqual(original.download);
    expect(archiveConfigs(normalizeAuto(JSON.parse(JSON.stringify(updated))))).toHaveLength(2);
    updated.archiveRules![0]!.rule.targetChatId = 400;
    expect(original.archiveRules[0]?.rule.targetChatId).toBe(300);
  });

  it("accepts a new destination and edits of either existing route without mutating settings", () => {
    const both = normalizeAuto({ archive: config(200), archiveRules: [config(300)] });
    const snapshot = JSON.stringify(both);
    expect(() => validateArchiveDestination(both, config(400))).not.toThrow();
    expect(() => validateArchiveDestination(both, config(300, false), "300")).not.toThrow();
    expect(() => validateArchiveDestination(both, config(400), 300)).not.toThrow();
    expect(() => validateArchiveDestination(both, config(200), "200")).not.toThrow();
    expect(JSON.stringify(both)).toBe(snapshot);
  });

  it("rejects a duplicate destination and an edit of a removed route", () => {
    const both = normalizeAuto({ archive: config(200), archiveRules: [config(300)] });
    expect(() => validateArchiveDestination(both, config(200))).toThrow("already exists");
    expect(() => validateArchiveDestination(both, config(200), 300)).toThrow("already exists");
    expect(() => validateArchiveDestination(both, config(400), 500)).toThrow("Refresh");
  });

  it("normalizes legacy configurations and retains an extra destination when the primary is disabled", () => {
    const legacy = normalizeAuto({ archive: config(200, false) });
    expect(archiveConfigs(legacy)).toHaveLength(1);
    expect(hasArchiveEnabled(legacy)).toBe(false);
    const extraOnly = normalizeAuto({ archiveRules: [config(300)] });
    expect(archiveConfigs(extraOnly).map((item) => item.rule.targetChatId)).toEqual([300]);
    expect(hasArchiveEnabled(extraOnly)).toBe(true);
  });
});
