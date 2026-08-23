export type ConnectionState =
  | "disconnected"
  | "starting"
  | "connecting"
  | "connected"
  | "reconnecting"
  | "error";

/** The values the tunnel binary accepts for --congestion-control. */
export type CongestionControl = "bbr" | "dcubic";

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

  // Tuning, passed through to the tunnel binary's own flags.
  /** BBR paces to measured bandwidth; dCUBIC backs off on loss. */
  congestionControl: CongestionControl;
  /** Let the kernel split large UDP writes: fewer syscalls, more throughput. */
  gso: boolean;
  /** Keep-alive interval in milliseconds; the tunnel's own default is 400. */
  keepAliveMs: number;
  /** Authoritative server to query directly, host:port. Empty means unused. */
  authoritative: string;
}

/** One live SOCKS5 connection, named from the request the relay forwarded. */
export interface ConnectionRow {
  id: number;
  /** Empty for a client that has not stated a destination yet. */
  host: string;
  port: number;
  bytesUp: number;
  bytesDown: number;
  ageSecs: number;
  startedMs: number;
}

/** Everything sent to one destination this session. */
export interface HostRow {
  host: string;
  bytesUp: number;
  bytesDown: number;
  bytesTotal: number;
  connections: number;
}

export interface TrafficSnapshot {
  connections: ConnectionRow[];
  topHosts: HostRow[];
  distinctHosts: number;
  totalConnections: number;
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
  /** Best rates seen this session. */
  peakRateUp: number;
  peakRateDown: number;
  traffic: TrafficSnapshot;
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
  /** Keep running in the tray when the window is closed. Windows only. */
  minimiseToTray: boolean;
  theme: "system" | "dark" | "light" | "blue";
  language: "system" | "en" | "ru";
  /**
   * Refuses new SOCKS5 connections and drops active ones while the tunnel is
   * not connected. Scoped to this app's own proxy port, not the whole system.
   */
  killSwitch: boolean;
  /** Absolute path to a copy of the chosen wallpaper image, or null. */
  wallpaperPath: string | null;
  /** How strongly the wallpaper is dimmed behind the panels, 0-90 percent. */
  wallpaperDim: number;
  /** Gaussian blur on the wallpaper, 0-40 px. */
  wallpaperBlur: number;
  /** Point the OS proxy setting at this app while connected. */
  systemProxy: boolean;
  /** Keep the graphs and connection table animating. */
  animations: boolean;
}

export const EMPTY_TRAFFIC: TrafficSnapshot = {
  connections: [],
  topHosts: [],
  distinctHosts: 0,
  totalConnections: 0,
};

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
  peakRateUp: 0,
  peakRateDown: 0,
  traffic: EMPTY_TRAFFIC,
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
    congestionControl: "bbr",
    gso: false,
    keepAliveMs: 400,
    authoritative: "",
  };
}
