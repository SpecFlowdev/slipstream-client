const UNITS = ["B", "KiB", "MiB", "GiB", "TiB"];

export function bytes(value: number): string {
  let n = Math.max(0, value);
  let unit = 0;
  while (n >= 1024 && unit < UNITS.length - 1) {
    n /= 1024;
    unit += 1;
  }
  const digits = unit === 0 || n >= 100 ? 0 : 1;
  return `${n.toFixed(digits)} ${UNITS[unit]}`;
}

export function rate(bytesPerSecond: number): string {
  return `${bytes(bytesPerSecond)}/s`;
}

export function duration(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (n: number) => n.toString().padStart(2, "0");
  return h > 0 ? `${h}:${pad(m)}:${pad(sec)}` : `${m}:${pad(sec)}`;
}

export function clockTime(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => n.toString().padStart(2, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
