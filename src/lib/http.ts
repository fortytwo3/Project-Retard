import { env } from "./env";

/**
 * Outbound HTTP with the manners a scraper owes the sites it reads: one request
 * per host at a time, a floor on the gap between them, a real User-Agent, and a
 * hard timeout so a hung socket cannot pin a request handler open.
 */

const HTTP_TIMEOUT_MS = 15_000;

/** Chrome on macOS. Sites serve bot-flavoured HTML to anything obviously automated. */
const USER_AGENT =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

/** Tail of the in-flight chain per host, so requests to one host serialise. */
const hostQueues = new Map<string, Promise<void>>();

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Serialise work against `host`, leaving at least `minIntervalMs` between the
 * end of one task and the start of the next.
 */
function throttle<T>(host: string, task: () => Promise<T>, minIntervalMs: number): Promise<T> {
  const previous = hostQueues.get(host) ?? Promise.resolve();

  const run = previous.then(async () => {
    await sleep(minIntervalMs);
    return task();
  });

  // Keep the chain alive regardless of whether this task succeeded.
  hostQueues.set(
    host,
    run.then(
      () => undefined,
      () => undefined,
    ),
  );

  return run;
}

export interface FetchOptions {
  method?: "GET" | "POST";
  headers?: Record<string, string>;
  body?: string;
  /** Skip the per-host throttle. Use for first-party APIs, not for scraping. */
  immediate?: boolean;
  /** Retries on 429/5xx/network errors. */
  retries?: number;
}

export class HttpError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly url: string,
  ) {
    super(message);
    this.name = "HttpError";
  }
}

async function once(url: string, options: FetchOptions): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), HTTP_TIMEOUT_MS);

  try {
    return await fetch(url, {
      method: options.method ?? "GET",
      headers: {
        "User-Agent": USER_AGENT,
        "Accept-Language": "en-US,en;q=0.9",
        ...options.headers,
      },
      body: options.body,
      signal: controller.signal,
      redirect: "follow",
    });
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Fetch with throttling and retry. Throws `HttpError` on a non-OK response so
 * providers can distinguish "site said no" from "site said nothing useful".
 */
export async function politeFetch(url: string, options: FetchOptions = {}): Promise<Response> {
  const host = new URL(url).host;
  const retries = options.retries ?? 2;

  const attempt = async (): Promise<Response> => {
    let lastError: unknown;

    for (let i = 0; i <= retries; i++) {
      if (i > 0) {
        // 1s, 2s, 4s — enough to clear a transient 429 without stalling the UI.
        await sleep(1000 * 2 ** (i - 1));
      }

      try {
        const response = await once(url, options);

        if (response.ok) return response;

        // 4xx other than 429 will not improve by asking again.
        if (response.status !== 429 && response.status < 500) {
          throw new HttpError(`${response.status} ${response.statusText}`, response.status, url);
        }

        lastError = new HttpError(`${response.status} ${response.statusText}`, response.status, url);
      } catch (error) {
        if (error instanceof HttpError && error.status !== 429 && error.status < 500) {
          throw error;
        }
        lastError = error;
      }
    }

    throw lastError instanceof Error ? lastError : new Error(`Request to ${url} failed`);
  };

  if (options.immediate) return attempt();
  return throttle(host, attempt, env.scrapeMinIntervalMs);
}

export async function fetchJson<T>(url: string, options: FetchOptions = {}): Promise<T> {
  const response = await politeFetch(url, {
    ...options,
    headers: { Accept: "application/json", ...options.headers },
  });
  return (await response.json()) as T;
}

export async function fetchHtml(url: string, options: FetchOptions = {}): Promise<string> {
  const response = await politeFetch(url, {
    ...options,
    headers: {
      Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      ...options.headers,
    },
  });
  return response.text();
}
