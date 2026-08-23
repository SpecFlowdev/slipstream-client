#!/usr/bin/env node
// Regenerates the README screenshots from the built interface.
//
// The app is driven through its real code path — the Tauri IPC and event
// layers are stubbed with representative data, and the charts are fed a
// minute of samples through the app's own status listener, so what is
// captured is the interface actually rendering, not a mock-up of it.
//
// Usage: npm run build && node scripts/screenshots.mjs
// Requires a static server for dist/ on PORT (started here automatically).

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

// Playwright is deliberately not a dependency of this project: it is needed
// only to regenerate these images, and pulling a browser automation stack
// into every install to do that is a poor trade. Resolve it from wherever it
// happens to be, and say plainly what to do when it is nowhere.
const { chromium } = await import("playwright").catch(() => {
  console.error(
    "This script needs Playwright, which is not a dependency of this project.\n" +
      "Install it first:  npm i --no-save playwright",
  );
  process.exit(1);
});

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const dist = path.join(root, "dist");
const out = path.join(root, "assets");
const PORT = Number(process.env.PORT || 8971);

const TYPES = {
  ".html": "text/html",
  ".js": "text/javascript",
  ".css": "text/css",
  ".svg": "image/svg+xml",
  ".png": "image/png",
};

const server = createServer(async (req, res) => {
  const rel = decodeURIComponent(new URL(req.url, "http://x").pathname);
  const file = path.join(dist, rel === "/" ? "index.html" : rel);
  try {
    const body = await readFile(file);
    res.writeHead(200, { "content-type": TYPES[path.extname(file)] ?? "application/octet-stream" });
    res.end(body);
  } catch {
    res.writeHead(404).end("not found");
  }
});
await new Promise((r) => server.listen(PORT, r));
const URL_BASE = `http://127.0.0.1:${PORT}/index.html`;

const PROFILES = [
  {
    id: "1", name: "Home server", domain: "t.example.com", resolver: "1.1.1.1:53",
    listenPort: 1080, socksUsername: "slipstream", socksPassword: "",
    cert: "-----BEGIN CERTIFICATE-----\nMIIBkTCB+wIJAKZ8s...\n-----END CERTIFICATE-----",
    congestionControl: "bbr", gso: true, keepAliveMs: 400, authoritative: "",
  },
  {
    id: "2", name: "Office box", domain: "tun.office.example", resolver: "9.9.9.9:53",
    listenPort: 1081, socksUsername: "", socksPassword: "", cert: "",
    congestionControl: "dcubic", gso: false, keepAliveMs: 600, authoritative: "",
  },
];

const traffic = {
  connections: [
    ["github.com", 443, 5_240_000, 410_000, 92],
    ["registry.npmjs.org", 443, 1_180_000, 220_000, 41],
    ["api.telegram.org", 443, 96_000, 210_000, 380],
    ["cdn.jsdelivr.net", 443, 74_000, 12_000, 7],
    ["duckduckgo.com", 443, 41_000, 9_800, 4],
  ].map(([host, port, bytesDown, bytesUp, ageSecs], i) => ({
    id: i + 1, host, port, bytesDown, bytesUp, ageSecs, startedMs: Date.now() - ageSecs * 1000,
  })),
  topHosts: [
    ["github.com", 18_400_000, 2_100_000, 24],
    ["cdn.jsdelivr.net", 9_800_000, 310_000, 12],
    ["www.wikipedia.org", 4_200_000, 190_000, 8],
    ["registry.npmjs.org", 3_100_000, 640_000, 31],
    ["api.telegram.org", 980_000, 1_450_000, 57],
    ["duckduckgo.com", 620_000, 44_000, 5],
  ].map(([host, bytesDown, bytesUp, connections]) => ({
    host, bytesDown, bytesUp, bytesTotal: bytesDown + bytesUp, connections,
  })),
  distinctHosts: 14,
  totalConnections: 137,
};

const STATUS = {
  state: "connected", profileId: "1",
  rateDown: 1_580_000, rateUp: 210_000,
  bytesDown: 38_500_000, bytesUp: 4_900_000,
  activeConnections: 5, uptimeSecs: 2447,
  peakRateDown: 3_900_000, peakRateUp: 720_000,
  message: null, traffic,
};

const LOGS = [
  ["info", "Starting slipstream-tunnel (bbr, GSO on, keep-alive 400 ms)"],
  ["info", "Resolving t.example.com via 1.1.1.1:53"],
  ["info", "QUIC handshake complete"],
  ["info", "Connection ready"],
  ["warn", "packet reorder window exceeded, retransmitting"],
  ["info", "socks5 client connected from 127.0.0.1:51422"],
  ["info", "socks5 client connected from 127.0.0.1:51430"],
].map(([level, message], i) => ({
  seq: i + 1, ts: Date.now() - (7 - i) * 6000, level, message,
}));

// Built from the banner's own palette — the same navy base and the same
// blue-to-teal accent — so the README reads as one piece from the banner
// down into the screenshot under it rather than changing colour halfway.
const WALLPAPER = `data:image/svg+xml,${encodeURIComponent(`
<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="1000">
 <defs>
  <linearGradient id="base" x1="0" y1="0" x2="1" y2="1">
   <stop offset="0%" stop-color="#0a1220"/>
   <stop offset="55%" stop-color="#0d1b2e"/>
   <stop offset="100%" stop-color="#071019"/>
  </linearGradient>
  <radialGradient id="glowA" cx="22%" cy="26%" r="52%">
   <stop offset="0%" stop-color="#38bdf8" stop-opacity="0.30"/>
   <stop offset="100%" stop-color="#38bdf8" stop-opacity="0"/>
  </radialGradient>
  <radialGradient id="glowB" cx="80%" cy="76%" r="50%">
   <stop offset="0%" stop-color="#22d3a8" stop-opacity="0.22"/>
   <stop offset="100%" stop-color="#22d3a8" stop-opacity="0"/>
  </radialGradient>
  <linearGradient id="streak" x1="0" y1="0" x2="1" y2="0">
   <stop offset="0%" stop-color="#38bdf8" stop-opacity="0"/>
   <stop offset="45%" stop-color="#38bdf8" stop-opacity="0.5"/>
   <stop offset="100%" stop-color="#22d3a8" stop-opacity="0"/>
  </linearGradient>
 </defs>
 <rect width="1600" height="1000" fill="url(#base)"/>
 <rect width="1600" height="1000" fill="url(#glowA)"/>
 <rect width="1600" height="1000" fill="url(#glowB)"/>
 ${Array.from({ length: 9 }, (_, i) =>
   `<rect x="0" y="${90 + i * 102}" width="1600" height="${i % 3 === 0 ? 2 : 1}" fill="url(#streak)" opacity="${0.5 - (i % 4) * 0.09}"/>`).join("")}
 ${Array.from({ length: 22 }, (_, i) =>
   `<rect x="${(i * 271) % 1500}" y="${88 + ((i * 307) % 830)}" width="${20 + (i % 5) * 14}" height="5" rx="2.5" fill="${i % 3 ? "#38bdf8" : "#22d3a8"}" opacity="${0.5 - (i % 5) * 0.07}"/>`).join("")}
</svg>`)}`;

const browser = await chromium.launch({ executablePath: "/opt/pw-browsers/chromium" });

async function session({ theme, language = "en", wallpaper = null, dim = 55, blur = 14 }) {
  const page = await browser.newPage({
    viewport: { width: 1020, height: 700 },
    deviceScaleFactor: 2,
  });
  await page.addInitScript(
    ({ profiles, status, logs, theme, language, wallpaper, dim, blur }) => {
      const callbacks = {};
      const listeners = {};
      let next = 0;
      window.__TAURI_INTERNALS__ = {
        transformCallback: (cb) => {
          const id = ++next;
          callbacks[id] = cb;
          return id;
        },
        // convertFileSrc is a pure string transform in the real app; the
        // data: URL used for the wallpaper here must survive it untouched.
        convertFileSrc: (p) => p,
        invoke: async (cmd, args) => {
          if (cmd === "plugin:event|listen") {
            (listeners[args.event] ??= []).push(args.handler);
            return next;
          }
          if (cmd === "plugin:event|unlisten") return null;
          if (cmd === "list_profiles") return profiles;
          if (cmd === "get_status") return status;
          if (cmd === "get_logs") return logs;
          if (cmd === "get_settings")
            return {
              autoReconnect: true, connectOnLaunch: false, minimiseToTray: true,
              theme, language, killSwitch: true,
              wallpaperPath: wallpaper, wallpaperDim: dim, wallpaperBlur: blur,
              systemProxy: false, animations: true,
            };
          return null;
        },
      };
      window.__TAURI__ = { event: {} };
      window.__emit = (payload) => {
        for (const id of listeners["status"] ?? []) callbacks[id]?.({ event: "status", id, payload });
      };
    },
    { profiles: PROFILES, status: STATUS, logs: LOGS, theme, language, wallpaper, dim, blur },
  );
  page.on("pageerror", (e) => console.error("[pageerror]", e.message));
  await page.goto(URL_BASE);
  await page.waitForTimeout(400);
  // A minute of plausible traffic, through the app's own listener, so the
  // charts draw from the real sample buffers.
  await page.evaluate((status) => {
    for (let i = 0; i < 60; i++) {
      const down = Math.max(0, 1_400_000 + Math.sin(i / 7) * 1_100_000 + (Math.random() - 0.5) * 240_000);
      const up = Math.max(0, 190_000 + Math.sin(i / 5 + 1) * 150_000 + (Math.random() - 0.5) * 38_000);
      window.__emit({ ...status, rateDown: down, rateUp: up });
    }
  }, STATUS);
  await page.waitForTimeout(400);
  return page;
}

async function tab(page, label) {
  await page.click(`nav >> text=${label}`);
  await page.waitForTimeout(350);
}

/** Scrolls the app's own pane, which is what actually scrolls, not the page. */
async function scrollMain(page, to = "bottom") {
  await page.evaluate((where) => {
    const main = document.querySelector("main");
    if (main) main.scrollTop = where === "bottom" ? main.scrollHeight : 0;
  }, to);
  await page.waitForTimeout(300);
}

const shot = (page, name) => page.screenshot({ path: path.join(out, `${name}.png`) });

// Hero: the traffic dashboard, dark gray.
{
  const page = await session({ theme: "dark" });
  await tab(page, "Traffic");
  await shot(page, "screenshot-traffic");
  await tab(page, "Connection");
  await shot(page, "screenshot-connection");
  await tab(page, "Servers");
  await shot(page, "screenshot-servers");
  await page.click("text=Edit");
  await page.waitForTimeout(300);
  await scrollMain(page);
  await shot(page, "screenshot-tuning");
  await tab(page, "Logs");
  await shot(page, "screenshot-logs");
  await tab(page, "Settings");
  await shot(page, "screenshot-settings");
  await page.close();
}

// Light theme on the dashboard. The blue theme is described in the README
// rather than shown: every image here has to earn a place in the page, and
// a third near-identical dashboard shot does not.
{
  const page = await session({ theme: "light" });
  await tab(page, "Traffic");
  await shot(page, "screenshot-light");
  await page.close();
}

// A wallpaper behind the frosted panels. Captured in both a dark theme and
// the light one: the light theme uses pale glass rather than dark, and
// showing both is what makes "works in any theme" a claim the page backs up
// rather than just asserts.
// The lead image pairs the navy wallpaper with the blue theme, whose panels
// are navy-tinted too, so it carries the banner's colour all the way down.
// The wallpaper is already dark, so it needs far less dimming than a photo
// would; the light capture keeps the heavier veil it does need.
for (const [theme, name, dim, blur] of [
  ["blue", "screenshot-wallpaper", 28, 10],
  // The light theme's veil is white, so a heavy one on a dark wallpaper
  // just turns it grey and the colour is lost entirely. Light glass over
  // dark navy needs very little of it.
  ["light", "screenshot-wallpaper-light", 22, 12],
]) {
  const page = await session({ theme, wallpaper: WALLPAPER, dim, blur });
  await tab(page, "Traffic");
  await shot(page, name);
  await page.close();
}

// Russian.
{
  const page = await session({ theme: "dark", language: "ru" });
  await tab(page, "Трафик");
  await shot(page, "screenshot-ru");
  await page.close();
}

await browser.close();
server.close();
console.log("Screenshots written to assets/");
