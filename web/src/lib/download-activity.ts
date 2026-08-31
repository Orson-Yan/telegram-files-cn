export interface DownloadListSnapshot {
  totalSize: number;
  totalCount: number;
  downloadedSize: number;
}

export interface DownloadOverviewStatistics {
  downloading?: number;
  completed?: number;
  queued?: number;
  downloadedSize?: number;
  queuedSize?: number;
  activeSize?: number;
  downloadLimit?: number;
}

export interface NormalizedDownloadOverview {
  downloading: number;
  completed: number;
  queued: number;
  downloadedSize: number;
  queuedSize: number;
  activeSize: number;
  downloadLimit: number;
}

export interface DownloadActivityState extends DownloadListSnapshot {
  speed: number;
  sessionDownloadedBytes: number;
  lastDownloadedSize: number;
  lastTimestamp: number;
  lastProgressAt: number;
}

export const EMPTY_DOWNLOAD_ACTIVITY: DownloadActivityState = {
  speed: 0,
  totalSize: 0,
  totalCount: 0,
  downloadedSize: 0,
  sessionDownloadedBytes: 0,
  lastDownloadedSize: 0,
  lastTimestamp: 0,
  lastProgressAt: 0,
};

function nonNegative(value: number | null | undefined) {
  return typeof value === "number" && Number.isFinite(value)
    ? Math.max(0, value)
    : 0;
}

export function normalizeDownloadOverview(
  statistics: DownloadOverviewStatistics,
): NormalizedDownloadOverview {
  return {
    downloading: nonNegative(statistics.downloading),
    completed: nonNegative(statistics.completed),
    queued: nonNegative(statistics.queued),
    downloadedSize: nonNegative(statistics.downloadedSize),
    queuedSize: nonNegative(statistics.queuedSize),
    activeSize: nonNegative(statistics.activeSize),
    downloadLimit: nonNegative(statistics.downloadLimit) || 5,
  };
}

export function updateDownloadActivity(
  previous: DownloadActivityState,
  snapshot: DownloadListSnapshot,
  timestamp: number,
): DownloadActivityState {
  const totalSize = nonNegative(snapshot.totalSize);
  const totalCount = nonNegative(snapshot.totalCount);
  const downloadedSize = nonNegative(snapshot.downloadedSize);

  if (totalCount === 0) {
    return {
      ...previous,
      speed: 0,
      totalSize: 0,
      totalCount: 0,
      downloadedSize: 0,
      lastDownloadedSize: 0,
      lastTimestamp: timestamp,
    };
  }

  const elapsedSeconds = (timestamp - previous.lastTimestamp) / 1000;
  const downloadedDelta = downloadedSize - previous.lastDownloadedSize;
  const hasComparableProgress =
    previous.lastTimestamp > 0 && elapsedSeconds > 0 && downloadedDelta > 0;

  return {
    ...previous,
    speed: hasComparableProgress
      ? downloadedDelta / elapsedSeconds
      : previous.speed,
    totalSize,
    totalCount,
    downloadedSize,
    lastDownloadedSize: downloadedSize,
    lastTimestamp: timestamp,
    lastProgressAt: hasComparableProgress
      ? timestamp
      : previous.lastProgressAt || timestamp,
  };
}

export function downloadedTrafficDelta(
  previous: number | undefined,
  current: number,
) {
  if (previous === undefined || current <= previous) {
    return 0;
  }
  return current - previous;
}
