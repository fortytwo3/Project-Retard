import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";

import { env } from "./env";

/**
 * Two-tier cache: an in-process map for the hot path, backed by JSON files so
 * the cache survives dev-server reloads. Scraped pages are expensive and
 * rate-limited, so re-fetching them on every hot reload is not an option.
 */

interface Entry<T> {
  value: T;
  expiresAt: number;
}

const memory = new Map<string, Entry<unknown>>();
const CACHE_DIR = join(process.cwd(), ".cache");

function keyToFile(key: string): string {
  const hash = createHash("sha256").update(key).digest("hex").slice(0, 32);
  return join(CACHE_DIR, `${hash}.json`);
}

async function readDisk<T>(key: string): Promise<Entry<T> | null> {
  try {
    const raw = await readFile(keyToFile(key), "utf8");
    return JSON.parse(raw) as Entry<T>;
  } catch {
    return null;
  }
}

async function writeDisk<T>(key: string, entry: Entry<T>): Promise<void> {
  try {
    await mkdir(CACHE_DIR, { recursive: true });
    await writeFile(keyToFile(key), JSON.stringify(entry), "utf8");
  } catch {
    // A cache that cannot persist is still a working cache.
  }
}

/**
 * Run `fn` unless a fresh cached value exists. Returns the value plus whether
 * it came from cache, because the UI shows that distinction.
 */
export async function cached<T>(
  key: string,
  fn: () => Promise<T>,
  ttlSeconds = env.cacheTtlSeconds,
): Promise<{ value: T; cached: boolean }> {
  // A non-positive TTL means caching off, which is what tests want and what
  // makes SCRAPE-heavy debugging bearable.
  if (ttlSeconds <= 0) {
    return { value: await fn(), cached: false };
  }

  const now = Date.now();

  const hot = memory.get(key) as Entry<T> | undefined;
  if (hot && hot.expiresAt > now) {
    return { value: hot.value, cached: true };
  }

  const cold = await readDisk<T>(key);
  if (cold && cold.expiresAt > now) {
    memory.set(key, cold);
    return { value: cold.value, cached: true };
  }

  const value = await fn();
  const entry: Entry<T> = { value, expiresAt: now + ttlSeconds * 1000 };
  memory.set(key, entry);
  await writeDisk(key, entry);
  return { value, cached: false };
}

/** Drop everything. Exposed for the "refresh prices" button. */
export function clearMemoryCache(): void {
  memory.clear();
}
