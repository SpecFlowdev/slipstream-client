#!/usr/bin/env node
// Gathers the bundles Tauri produced into release/ under predictable names, and
// builds the plain tar.gz that Tauri has no bundler for.
//
// Every file is published with a .sha256 beside it.

import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { copyFile, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const version = process.env.VERSION;
const target = process.env.TARGET;

if (!version || !target) {
  console.error("VERSION and TARGET must be set");
  process.exit(1);
}

const windows = target.includes("windows");
const arch = target.startsWith("aarch64") ? "arm64" : "x86_64";
const platform = windows ? "windows" : "linux";
const base = `slipstream-client-${version}-${platform}-${arch}`;

const bundleDir = path.join(root, "src-tauri", "target", target, "release", "bundle");
const releaseDir = path.join(root, "release");
const binDir = path.join(root, "src-tauri", "target", target, "release");

await mkdir(releaseDir, { recursive: true });

async function findBundle(subdir, extension) {
  const dir = path.join(bundleDir, subdir);
  let entries;
  try {
    entries = await readdir(dir);
  } catch {
    return null;
  }
  const hit = entries.find((name) => name.toLowerCase().endsWith(extension));
  return hit ? path.join(dir, hit) : null;
}

const published = [];

async function publish(source, name) {
  const dest = path.join(releaseDir, name);
  await copyFile(source, dest);
  const digest = createHash("sha256").update(await readFile(dest)).digest("hex");
  await writeFile(`${dest}.sha256`, `${digest}  ${name}\n`);
  published.push(name);
  console.log(`${name}  ${digest}`);
}

if (windows) {
  const exe = await findBundle("nsis", ".exe");
  if (!exe) throw new Error("No NSIS installer was produced");
  await publish(exe, `${base}-setup.exe`);
} else {
  const deb = await findBundle("deb", ".deb");
  if (!deb) throw new Error("No .deb was produced");
  await publish(deb, `${base}.deb`);

  const rpm = await findBundle("rpm", ".rpm");
  if (!rpm) throw new Error("No .rpm was produced");
  await publish(rpm, `${base}.rpm`);

  const appimage = await findBundle("appimage", ".appimage");
  if (appimage) await publish(appimage, `${base}.AppImage`);
}

// The portable archive: the app and its tunnel side by side, which is how the
// packaged layout arranges them too.
const work = await mkdtemp(path.join(tmpdir(), "slipstream-portable-"));
try {
  const stageName = `slipstream-client-${version}`;
  const stage = path.join(work, stageName);
  await mkdir(stage, { recursive: true });

  const exeSuffix = windows ? ".exe" : "";
  await copyFile(
    path.join(binDir, `slipstream-client${exeSuffix}`),
    path.join(stage, `slipstream-client${exeSuffix}`),
  );
  await copyFile(
    path.join(root, "src-tauri", "binaries", `slipstream-tunnel-${target}${exeSuffix}`),
    path.join(stage, `slipstream-tunnel${exeSuffix}`),
  );
  await copyFile(path.join(root, "LICENSE"), path.join(stage, "LICENSE"));
  await copyFile(path.join(root, "README.md"), path.join(stage, "README.md"));

  // Relative paths only, run from the staging directory: tar on Windows reads
  // a leading "C:" as a remote host spec and refuses the absolute path.
  const archiveName = `${base}.tar.gz`;
  execFileSync("tar", ["-czf", archiveName, stageName], { cwd: work, stdio: "inherit" });
  await publish(path.join(work, archiveName), archiveName);
} finally {
  await rm(work, { recursive: true, force: true });
}

console.log(`\n${published.length} files staged in release/`);
