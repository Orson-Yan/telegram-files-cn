"use client";

import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";
import useWebSocket, { ReadyState } from "react-use-websocket";
import {
  type TelegramError,
  type WebSocketMessage,
  WebSocketMessageType,
} from "@/lib/websocket-types";
import { useToast } from "./use-toast";
import { useDebounce } from "use-debounce";
import { getWsUrl } from "@/lib/api";
import { useSearchParams } from "next/navigation";
import { useSWRConfig } from "swr";
import { type TDFile } from "@/lib/types";
import {
  downloadedTrafficDelta,
  EMPTY_DOWNLOAD_ACTIVITY,
  type DownloadActivityState,
  updateDownloadActivity,
} from "@/lib/download-activity";

interface WebsocketContextType {
  sendMessage: (message: WebSocketMessage) => void;
  lastJsonMessage: WebSocketMessage | null;
  connectionStatus: string;
  isReady: boolean;
  accountDownloadSpeed: number;
  downloadActivity: DownloadActivityState;
  reconnect: () => void;
  telegramConnectionState: string | null;
}

const WebSocketContext = createContext<WebsocketContextType | undefined>(
  undefined,
);

interface WebSocketProviderProps {
  children: ReactNode;
}

export const WebSocketProvider: React.FC<WebSocketProviderProps> = ({
  children,
}) => {
  const wsUrl = getWsUrl();
  const searchParams = useSearchParams();
  const [isReady, setIsReady] = useState(false);
  const [downloadActivity, setDownloadActivity] = useState(
    EMPTY_DOWNLOAD_ACTIVITY,
  );
  const downloadedByFile = useRef(new Map<string, number>());
  const { toast } = useToast();
  const { mutate } = useSWRConfig();
  const [debounceSpeed] = useDebounce(downloadActivity.speed, 300, {
    leading: true,
    maxWait: 1000,
  });

  const [reconnectNonce, setReconnectNonce] = useState(0);
  const [telegramConnectionState, setTelegramConnectionState] = useState<
    string | null
  >(null);

  const { sendMessage, lastJsonMessage, readyState, getWebSocket } =
    useWebSocket<WebSocketMessage>(
      `${wsUrl}?telegramId=${searchParams.get("id") ?? ""}&_r=${reconnectNonce}`,
      {
        // Keep retrying (essentially) forever with exponential backoff capped at 30s, and also
        // retry on error events — so a transient outage recovers on its own instead of giving up.
        shouldReconnect: () => true,
        reconnectAttempts: 999,
        reconnectInterval: (attemptNumber) =>
          Math.min(1000 * 2 ** attemptNumber, 30000),
        retryOnError: true,
      },
    );

  useEffect(() => {
    const timer = window.setInterval(() => {
      setDownloadActivity((previous) => {
        if (
          previous.speed === 0 ||
          previous.totalCount === 0 ||
          Date.now() - previous.lastProgressAt <= 5_000
        ) {
          return previous;
        }
        return { ...previous, speed: 0 };
      });
    }, 2_000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (readyState !== ReadyState.CONNECTING) return;

    // A failed WebSocket handshake can leave Chromium in CONNECTING without an
    // error/close event. Rotate the URL so the hook tears down that socket and
    // retries after the API becomes available.
    const timeout = window.setTimeout(() => {
      if (getWebSocket()?.readyState === WebSocket.CONNECTING) {
        setReconnectNonce((nonce) => nonce + 1);
      }
    }, 10000);
    return () => window.clearTimeout(timeout);
  }, [getWebSocket, readyState]);

  // Force a fresh connection (resets the backoff/attempt counter); used by the manual
  // "reconnect" affordance and when the network comes back.
  const reconnect = useCallback(() => {
    setReconnectNonce((n) => n + 1);
  }, []);

  useEffect(() => {
    const maybeReconnect = () => {
      if (
        readyState !== ReadyState.OPEN &&
        readyState !== ReadyState.CONNECTING
      ) {
        setReconnectNonce((n) => n + 1);
      }
    };
    window.addEventListener("online", maybeReconnect);
    return () => {
      window.removeEventListener("online", maybeReconnect);
    };
  }, [readyState]);

  const connectionStatus = {
    [ReadyState.CONNECTING]: "Connecting",
    [ReadyState.OPEN]: "Open",
    [ReadyState.CLOSING]: "Closing",
    [ReadyState.CLOSED]: "Closed",
    [ReadyState.UNINSTANTIATED]: "Uninstantiated",
  }[readyState];

  useEffect(() => {
    setIsReady(readyState === ReadyState.OPEN);
  }, [readyState]);

  useEffect(() => {
    if (lastJsonMessage !== null) {
      // console.log(
      //   `🐄: ${JSON.stringify(lastJsonMessage, null, 2)}`,
      // )
      try {
        const payload: WebSocketMessage = lastJsonMessage;
        const timestamp = payload.timestamp;
        switch (payload.type) {
          case WebSocketMessageType.AUTHORIZATION:
            void mutate("/telegrams");
            break;
          case WebSocketMessageType.FILE_UPDATE: {
            const file = (payload.data as { file?: TDFile })?.file;
            const uniqueId = file?.remote?.uniqueId;
            if (!file || !uniqueId || !file.local) {
              break;
            }
            const current = Math.max(0, file.local.downloadedSize);
            const previous = downloadedByFile.current.get(uniqueId);
            const delta = downloadedTrafficDelta(previous, current);
            downloadedByFile.current.set(uniqueId, current);
            if (file.local.isDownloadingCompleted) {
              downloadedByFile.current.delete(uniqueId);
            }
            if (delta > 0) {
              setDownloadActivity((activity) => ({
                ...activity,
                sessionDownloadedBytes: activity.sessionDownloadedBytes + delta,
                lastProgressAt: timestamp,
              }));
            }
            break;
          }
          case WebSocketMessageType.CONNECTION:
            setTelegramConnectionState(
              (payload.data as { state?: string })?.state ?? null,
            );
            break;
          case WebSocketMessageType.ERROR:
            toast({
              variant: "error",
              description: (payload.data as TelegramError).message,
            });
            break;
          case WebSocketMessageType.FILE_DOWNLOAD: {
            const { downloadedSize, totalCount, totalSize } = payload.data as {
              totalSize: number;
              totalCount: number;
              downloadedSize: number;
            };
            setDownloadActivity((previous) =>
              updateDownloadActivity(
                previous,
                { downloadedSize, totalCount, totalSize },
                timestamp,
              ),
            );
            break;
          }
        }
      } catch (error) {
        console.error("Failed to parse WebSocket message:", error);
      }
    }
  }, [lastJsonMessage, mutate, toast]);

  const sendWebSocketMessage = useCallback(
    (message: WebSocketMessage) => {
      if (isReady) {
        sendMessage(JSON.stringify(message));
      }
    },
    [isReady, sendMessage],
  );

  return (
    <WebSocketContext.Provider
      value={{
        sendMessage: sendWebSocketMessage,
        lastJsonMessage,
        connectionStatus,
        isReady,
        accountDownloadSpeed: debounceSpeed,
        downloadActivity: { ...downloadActivity, speed: debounceSpeed },
        reconnect,
        telegramConnectionState,
      }}
    >
      {children}
    </WebSocketContext.Provider>
  );
};

export function useWebsocket() {
  const context = useContext(WebSocketContext);
  if (context === undefined) {
    throw new Error(
      "useTelegramWebSocket must be used within a WebSocketProvider",
    );
  }
  return context;
}
