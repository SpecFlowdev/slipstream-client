export type ConnectionState =
  | "disconnected"
  | "starting"
  | "connecting"
  | "connected"
  | "reconnecting"
  | "error";

export interface Profile {
  id: string;
  name: string;
  /** Tunnel domain delegated to the server. */
  domain: string;
  /** Resolver the client sends its DNS queries to, host:port. */
  resolver: string;
  /** PEM certificate pinned to the server leaf. Empty means pinning is off. */
  cert: string;
  /** Local port the app exposes as a SOCKS5 proxy. */
  listenPort: number;
  /** SOCKS5 credentials expected by the proxy behind the tunnel. */
  socksUsername: string;
  socksPassword: string;
}

export interface Status {
  state: ConnectionState;
  profileId: string | null;
  /** Populated when state is "error". */
  message: string | null;
  /** Seconds since the tunnel reached "connected". */
  uptimeSecs: number;
  bytesUp: number;
  bytesDown: number;
  /** Bytes per second over the last sample window. */
  rateUp: number;
  rateDown: number;
  activeConnections: number;
}

export type LogLevel = "trace" | "debug" | "info" | "warn" | "error";

export interface LogLine {
  seq: number;
  /** Milliseconds since the Unix epoch. */
  ts: number;
  level: LogLevel;
  message: string;
}

export interface Settings {
  /** Reconnect automatically when the tunnel drops. */
  autoReconnect: boolean;
  /** Start the last used profile when the app launches. */
  connectOnLaunch: boolean;
  /** Keep running in the tray when the window is closed. */
  minimiseToTray: boolean;
  theme: "system" | "dark" | "light";
  language: "system" | "en" | "ru";
}

export const EMPTY_STATUS: Status = {
  state: "disconnected",
  profileId: null,
  message: null,
  uptimeSecs: 0,
  bytesUp: 0,
  bytesDown: 0,
  rateUp: 0,
  rateDown: 0,
  activeConnections: 0,
};

export function blankProfile(): Profile {
  return {
    id: "",
    name: "",
    domain: "",
    resolver: "",
    cert: "",
    listenPort: 1080,
    socksUsername: "",
    socksPassword: "",
  };
}
