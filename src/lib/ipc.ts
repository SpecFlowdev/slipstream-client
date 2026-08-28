import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type { ImportedProfile, LogLine, Profile, Rule, SessionRecord, Settings, Status } from "./types";

export const listProfiles = () => invoke<Profile[]>("list_profiles");
export const saveProfile = (profile: Profile) => invoke<Profile>("save_profile", { profile });
export const deleteProfile = (id: string) => invoke<void>("delete_profile", { id });

/** Encodes a profile as a `slipstream://p?...` link, to show as a QR code or copy. */
export const shareProfile = (id: string) => invoke<string>("share_profile", { id });
/** Decodes a link — typed, pasted, or read from a scanned QR image — and saves it as a new profile. */
export const importProfile = (text: string) => invoke<ImportedProfile>("import_profile", { text });

export const connect = (profileId: string) => invoke<void>("connect", { profileId });
export const disconnect = () => invoke<void>("disconnect");
export const getStatus = () => invoke<Status>("get_status");

export const getLogs = () => invoke<LogLine[]>("get_logs");
export const clearLogs = () => invoke<void>("clear_logs");

export const getSettings = () => invoke<Settings>("get_settings");
export const saveSettings = (settings: Settings) => invoke<Settings>("save_settings", { settings });

export const listRules = () => invoke<Rule[]>("list_rules");
/** Saves the rules and applies them to the running relay straight away. */
export const saveRules = (rules: Rule[]) => invoke<Rule[]>("save_rules", { rules });

export const getHistory = () => invoke<SessionRecord[]>("get_history");
export const clearHistory = () => invoke<void>("clear_history");

/** Reads a certificate file chosen by the user and returns its PEM contents. */
export const readCertFile = (path: string) => invoke<string>("read_cert_file", { path });

/** Copies the chosen image into the app's config directory; returns its new path. */
export const setWallpaper = (path: string) => invoke<string>("set_wallpaper", { path });
export const clearWallpaper = () => invoke<void>("clear_wallpaper");

export const onStatus = (fn: (status: Status) => void): Promise<UnlistenFn> =>
  listen<Status>("status", (event) => fn(event.payload));

export const onLog = (fn: (line: LogLine) => void): Promise<UnlistenFn> =>
  listen<LogLine>("log", (event) => fn(event.payload));
