import { cached } from "./cache";
import { env } from "./env";
import { fetchJson } from "./http";
import type { Card, CardCandidate, ScanParse } from "./types";

const API_BASE = "https://api.pokemontcg.io/v2";

/** Shape of the upstream payload, narrowed to the fields we consume. */
interface ApiCard {
  id: string;
  name: string;
  number: string;
  rarity?: string;
  set: {
    id: string;
    name: string;
    series: string;
    printedTotal?: number;
    releaseDate?: string;
  };
  images: { small: string; large: string };
  tcgplayer?: { url?: string; prices?: unknown };
  cardmarket?: { url?: string; prices?: unknown };
}

function headers(): Record<string, string> {
  return env.pokemontcgApiKey ? { "X-Api-Key": env.pokemontcgApiKey } : {};
}

function toCard(api: ApiCard): Card {
  return {
    id: api.id,
    name: api.name,
    number: api.number,
    printedTotal: api.set.printedTotal ?? null,
    setId: api.set.id,
    setName: api.set.name,
    setSeries: api.set.series,
    setReleaseDate: api.set.releaseDate ?? null,
    rarity: api.rarity ?? null,
    images: api.images,
    tcgplayerUrl: api.tcgplayer?.url ?? null,
    cardmarketUrl: api.cardmarket?.url ?? null,
    rawTcgplayer: api.tcgplayer?.prices ?? null,
    rawCardmarket: api.cardmarket?.prices ?? null,
  };
}

async function query(q: string, pageSize = 40): Promise<Card[]> {
  const url = `${API_BASE}/cards?q=${encodeURIComponent(q)}&pageSize=${pageSize}&orderBy=-set.releaseDate`;

  const { value } = await cached(
    `ptcg:${url}`,
    () => fetchJson<{ data: ApiCard[] }>(url, { headers: headers(), immediate: true }),
    // Card metadata is static; only the embedded prices move, and those get a
    // shorter TTL of their own in the provider.
    60 * 60 * 12,
  );

  return (value.data ?? []).map(toCard);
}

export async function getCardById(id: string): Promise<Card | null> {
  const url = `${API_BASE}/cards/${encodeURIComponent(id)}`;
  try {
    const { value } = await cached(
      `ptcg:card:${id}`,
      () => fetchJson<{ data: ApiCard }>(url, { headers: headers(), immediate: true }),
      60 * 60 * 12,
    );
    return value.data ? toCard(value.data) : null;
  } catch {
    return null;
  }
}

function normalise(text: string): string {
  return text
    .toLowerCase()
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9 ]/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

/** Levenshtein distance, iterative with a single row of state. */
function editDistance(a: string, b: string): number {
  if (a === b) return 0;
  if (a.length === 0) return b.length;
  if (b.length === 0) return a.length;

  let previous = Array.from({ length: b.length + 1 }, (_, i) => i);

  for (let i = 1; i <= a.length; i++) {
    const current = [i];
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      current[j] = Math.min(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost);
    }
    previous = current;
  }

  return previous[b.length];
}

/** 1 for identical strings, 0 for entirely different ones. */
function similarity(a: string, b: string): number {
  const longest = Math.max(a.length, b.length);
  if (longest === 0) return 1;
  return 1 - editDistance(a, b) / longest;
}

/** Strip leading zeros so "058" and "58" compare equal. */
function normaliseNumber(value: string): string {
  return value.toUpperCase().replace(/^([A-Z]*)0+(\d)/, "$1$2");
}

/**
 * Score a card against what OCR saw. Weighted so that the collector number —
 * the most reliably-read field — can carry a match when the name is mangled,
 * but a confident name match alone is still enough to surface candidates.
 */
function scoreCard(card: Card, parse: ScanParse): { score: number; reasons: string[] } {
  const reasons: string[] = [];
  let score = 0;

  if (parse.name) {
    const nameScore = similarity(normalise(card.name), normalise(parse.name));
    score += nameScore * 0.5;
    if (nameScore > 0.95) reasons.push("Exact name match");
    else if (nameScore > 0.7) reasons.push("Close name match");
  }

  if (parse.number && normaliseNumber(card.number) === normaliseNumber(parse.number)) {
    score += 0.3;
    reasons.push(`Card number ${card.number}`);
  }

  if (parse.printedTotal && card.printedTotal === parse.printedTotal) {
    score += 0.2;
    reasons.push(`Set size ${card.printedTotal} matches`);
  }

  if (parse.setCode && card.setId.toUpperCase().includes(parse.setCode)) {
    score += 0.1;
    reasons.push(`Set code ${parse.setCode}`);
  }

  return { score: Math.min(score, 1), reasons };
}

/**
 * Find the cards a scan might be showing.
 *
 * Runs several queries because the API does not do fuzzy matching: a name with
 * one OCR error returns nothing, so we also search on the collector number
 * alone, which is short enough to usually survive OCR intact. Results are
 * merged and ranked locally.
 */
export async function identifyCard(parse: ScanParse): Promise<CardCandidate[]> {
  const queries: string[] = [];

  if (parse.name && parse.number) {
    queries.push(`name:"${parse.name}" number:"${parse.number.replace(/^0+/, "")}"`);
  }
  if (parse.name) {
    // Wildcard the last word so a truncated read still hits.
    const words = parse.name.split(" ");
    const wildcard = [...words.slice(0, -1), `${words[words.length - 1]}*`].join(" ");
    queries.push(`name:"${parse.name}"`);
    queries.push(`name:${wildcard}`);
  }
  if (parse.number) {
    const bare = parse.number.replace(/^0+/, "");
    queries.push(
      parse.printedTotal
        ? `number:"${bare}" set.printedTotal:${parse.printedTotal}`
        : `number:"${bare}"`,
    );
  }

  if (queries.length === 0) return [];

  const settled = await Promise.allSettled(queries.map((q) => query(q)));

  const byId = new Map<string, Card>();
  for (const result of settled) {
    if (result.status !== "fulfilled") continue;
    for (const card of result.value) byId.set(card.id, card);
  }

  return [...byId.values()]
    .map((card) => ({ card, ...scoreCard(card, parse) }))
    .filter((candidate) => candidate.score > 0.35)
    .sort((a, b) => b.score - a.score)
    .slice(0, 24);
}

/** Free-text search backing the manual-entry fallback. */
export async function searchCards(term: string): Promise<Card[]> {
  const cleaned = term.trim();
  if (cleaned.length < 2) return [];

  const escaped = cleaned.replace(/"/g, "");
  return query(`name:"${escaped}*"`, 30);
}

export const __testing = { similarity, normaliseNumber, scoreCard, normalise };
