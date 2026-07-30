"use client";

import type { Card, HistoryEntry, PriceReport } from "./types";

/**
 * Scan history, kept in localStorage.
 *
 * Deliberately client-only: there are no accounts in this app, and a scan
 * history is the kind of thing that should not silently end up on a server.
 */

const STORAGE_KEY = "paladex-ex:history";
const MAX_ENTRIES = 200;

function read(): HistoryEntry[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as HistoryEntry[]) : [];
  } catch {
    return [];
  }
}

function write(entries: HistoryEntry[]): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(entries.slice(0, MAX_ENTRIES)));
  } catch {
    // Quota exceeded or storage disabled — history is a convenience, not state
    // the app depends on.
  }
}

export function getHistory(): HistoryEntry[] {
  return read();
}

/** Record a scan along with what the card was worth at that moment. */
export function addToHistory(card: Card, report: PriceReport): HistoryEntry {
  const entry: HistoryEntry = {
    id: `${card.id}:${Date.now()}`,
    cardId: card.id,
    cardName: card.name,
    setName: card.setName,
    imageUrl: card.images.small,
    scannedAt: new Date().toISOString(),
    valueAtScan: report.consensus.amount,
    currency: report.consensus.currency,
  };

  write([entry, ...read()]);
  return entry;
}

export function removeFromHistory(id: string): void {
  write(read().filter((entry) => entry.id !== id));
}

export function clearHistory(): void {
  write([]);
}

/**
 * Total of the saved values. Only sums entries sharing the leading currency,
 * since the app does no FX conversion and a mixed-currency total would be a
 * made-up number.
 */
export function portfolioValue(entries: HistoryEntry[]): {
  total: number;
  currency: string;
  counted: number;
  skipped: number;
} {
  const priced = entries.filter((e) => e.valueAtScan !== null);
  if (priced.length === 0) return { total: 0, currency: "USD", counted: 0, skipped: entries.length };

  const currency = priced[0].currency;
  const matching = priced.filter((e) => e.currency === currency);

  return {
    total: matching.reduce((sum, e) => sum + (e.valueAtScan ?? 0), 0),
    currency,
    counted: matching.length,
    skipped: entries.length - matching.length,
  };
}
