import type { Auto } from "@/lib/types";

export function createDefaultAuto(): Auto {
  return {
    preload: { enabled: false },
    download: {
      enabled: false,
      rule: {
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
        targetChatId: 0,
        mode: "COPY",
        scope: "ALL_MESSAGES",
        fileTypes: [],
        query: "",
        filterExpr: "",
        preserveCaption: true,
        disableNotification: true,
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
  };
}
