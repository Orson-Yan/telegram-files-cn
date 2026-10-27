import type { Auto } from "@/lib/types";

export function createDefaultAuto(): Auto {
  return {
    preload: { enabled: false },
    download: {
      enabled: false,
      rule: {
        schemaVersion: 1,
        query: "",
        fileTypes: [],
        downloadHistory: true,
        downloadCommentFiles: false,
        filterExpr: "",
      },
    },
    transfer: {
      enabled: false,
      rule: {
        transferHistory: false,
        sourceTopicId: 0,
        destination: "",
        transferPolicy: "GROUP_BY_CHAT",
        duplicationPolicy: "RENAME",
        useCaptionName: false,
        extra: {},
      },
    },
    archive: {
      enabled: false,
      rule: {
        sourceTopicId: 0,
        targetChatId: 0,
        targetTopicId: 0,
        topicMode: "MERGE",
        mode: "COPY",
        scope: "ALL_MESSAGES",
        fileTypes: [],
        query: "",
        filterExpr: "",
        preserveCaption: true,
        disableNotification: true,
        strictOrder: false,
        recoveryEnabled: true,
        initialSyncMode: "NOW",
      },
    },
  };
}

export function normalizeAuto(value?: Partial<Auto>): Auto {
  const defaults = createDefaultAuto();
  return {
    preload: { ...defaults.preload, ...value?.preload },
    download: {
      ...defaults.download,
      ...value?.download,
      rule: { ...defaults.download.rule, ...value?.download?.rule },
    },
    transfer: {
      ...defaults.transfer,
      ...value?.transfer,
      rule: {
        ...defaults.transfer.rule,
        ...value?.transfer?.rule,
        extra: {
          ...defaults.transfer.rule.extra,
          ...value?.transfer?.rule?.extra,
        },
      },
    },
    archive: {
      ...defaults.archive,
      ...value?.archive,
      rule: { ...defaults.archive.rule, ...value?.archive?.rule },
    },
    archiveRules: (value?.archiveRules ?? []).map((config) => ({
      ...defaults.archive,
      ...config,
      rule: { ...defaults.archive.rule, ...config.rule },
    })),
  };
}

export function archiveConfigs(value?: Partial<Auto>): Auto["archive"][] {
  return [value?.archive, ...(value?.archiveRules ?? [])].filter(
    (config): config is Auto["archive"] => Boolean(config?.rule?.targetChatId),
  );
}

export function hasArchiveEnabled(value?: Partial<Auto>): boolean {
  return archiveConfigs(value).some((config) => config.enabled);
}

/** Validate the selected route; the server merges the update into its latest settings. */
export function validateArchiveDestination(
  value: Auto,
  config: Auto["archive"],
  previousTargetChatId?: string | number,
): void {
  const configs = archiveConfigs(value);
  const previous = previousTargetChatId === undefined
    ? undefined : String(previousTargetChatId);
  const target = String(config.rule.targetChatId);
  if (configs.some((item) => String(item.rule.targetChatId) === target
      && String(item.rule.targetChatId) !== previous)) {
    throw new Error("A rule already exists for this source and destination");
  }
  if (previous !== undefined) {
    if (!configs.some((item) => String(item.rule.targetChatId) === previous)) {
      throw new Error("The rule has changed. Refresh the rules and try again");
    }
  }
}
