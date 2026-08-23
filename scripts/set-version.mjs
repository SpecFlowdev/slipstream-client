#!/usr/bin/env node
// Stamps a release version across the places that must agree: the Tauri
// config, the Cargo manifest, package.json, and the version shown in the app.

import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const version = process.argv[2];

if (!/^\d+\.\d+\.\d+$/.test(version ?? "")) {
  console.error(`Version must look like 1.2.3, got: ${version}`);
  process.exit(1);
}

// Guards against a silently missed file: the pattern must match, though the
// value may already be the one requested.
async function edit(file, pattern, replacement) {
  const full = path.join(root, file);
  const before = await readFile(full, "utf8");
  if (!pattern.test(before)) {
    throw new Error(`No version field found in ${file}; its format changed`);
  }
  await writeFile(full, before.replace(pattern, replacement));
  console.log(`${file} -> ${version}`);
}

await edit("src-tauri/tauri.conf.json", /("version":\s*)"[^"]+"/, `$1"${version}"`);
await edit("src-tauri/Cargo.toml", /^version = "[^"]+"/m, `version = "${version}"`);
await edit("package.json", /("version":\s*)"[^"]+"/, `$1"${version}"`);
await edit("src/App.svelte", /const VERSION = "[^"]+"/, `const VERSION = "${version}"`);
