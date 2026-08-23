#!/usr/bin/env node
// Places the slipstream-client tunnel binary where Tauri expects its sidecar.
//
// The binary is a prebuilt upstream release, verified against a checksum pinned
// here rather than only against the .sha256 published beside it — an attacker
// able to replace one could replace the other.

import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { createWriteStream } from "node:fs";
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const VERSION = "v0.1.1";
const REPO = "Mygod/slipstream-rust";

// Rust target triple -> upstream asset name and its SHA256.
const ASSETS = {
  "x86_64-unknown-linux-gnu": {
    asset: "slipstream-linux-x86_64",
    ext: "tar.gz",
    sha256: "5572d407d685a4a87e4de5a7121566d48ac719068e50ad69705793dc6d9ff682",
  },
  "aarch64-unknown-linux-gnu": {
    asset: "slipstream-linux-arm64",
    ext: "tar.gz",
    sha256: "4220db98c49617c8453514afe6ea7455ccc6f31aad14f206643e9d81cd9b7e31",
  },
  "x86_64-pc-windows-msvc": {
    asset: "slipstream-windows-x86_64",
    ext: "zip",
    sha256: "7efc45bafab08aebacba10aa19562793de29dcd67e89f75ce8a3942b8da1e59e",
  },
  "aarch64-pc-windows-msvc": {
    asset: "slipstream-windows-arm64",
    ext: "zip",
    sha256: "bfb36b6cf7b87601906e0c6e2c26429cbb10a9b0b7b4bf21caa3531a8ad17235",
  },
};

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const outDir = path.join(root, "src-tauri", "binaries");

function hostTriple() {
  const out = execFileSync("rustc", ["-vV"], { encoding: "utf8" });
  const match = out.match(/^host:\s*(\S+)$/m);
  if (!match) throw new Error("Could not read the host target triple from rustc");
  return match[1];
}

async function download(url, dest) {
  const response = await fetch(url, { redirect: "follow" });
  if (!response.ok) {
    throw new Error(`Download failed (${response.status}): ${url}`);
  }
  await pipeline(Readable.fromWeb(response.body), createWriteStream(dest));
}

async function verify(file, expected) {
  const hash = createHash("sha256").update(await readFile(file)).digest("hex");
  if (hash !== expected) {
    throw new Error(`Checksum mismatch for ${path.basename(file)}\n  expected ${expected}\n  got      ${hash}`);
  }
}

async function main() {
  const triple = process.argv[2] ?? hostTriple();
  const spec = ASSETS[triple];
  if (!spec) {
    throw new Error(
      `No prebuilt tunnel binary for ${triple}. Known targets: ${Object.keys(ASSETS).join(", ")}`,
    );
  }

  const windows = triple.includes("windows");
  const exe = windows ? ".exe" : "";
  const target = path.join(outDir, `slipstream-tunnel-${triple}${exe}`);

  const work = await mkdtemp(path.join(tmpdir(), "slipstream-sidecar-"));
  try {
    const archive = path.join(work, `${spec.asset}.${spec.ext}`);
    const url = `https://github.com/${REPO}/releases/download/${VERSION}/${spec.asset}.${spec.ext}`;

    console.log(`Downloading ${spec.asset}.${spec.ext} (${VERSION})`);
    await download(url, archive);

    console.log("Verifying SHA256");
    await verify(archive, spec.sha256);

    if (spec.ext === "tar.gz") {
      execFileSync("tar", ["-xzf", archive, "-C", work], { stdio: "inherit" });
    } else {
      // Available on Windows runners and modern Linux images alike.
      execFileSync("unzip", ["-q", "-o", archive, "-d", work], { stdio: "inherit" });
    }

    // tar.gz keeps the assets in a directory; the zip flattens them.
    const candidates = [
      path.join(work, spec.asset, `slipstream-client${exe}`),
      path.join(work, `slipstream-client${exe}`),
    ];
    let payload = null;
    for (const candidate of candidates) {
      try {
        payload = await readFile(candidate);
        break;
      } catch {
        // try the next layout
      }
    }
    if (!payload) {
      throw new Error("The archive did not contain slipstream-client");
    }

    await mkdir(outDir, { recursive: true });
    await writeFile(target, payload);
    if (!windows) await chmod(target, 0o755);
    console.log(`Sidecar ready: ${path.relative(root, target)}`);
  } finally {
    await rm(work, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error(String(err.message ?? err));
  process.exit(1);
});
