"use client";

import Image from "next/image";
import Link from "next/link";
import { useEffect, useState } from "react";

import { clearHistory, getHistory, portfolioValue, removeFromHistory } from "@/lib/history";
import { formatMoney } from "@/lib/money";
import type { Currency, HistoryEntry } from "@/lib/types";

export default function HistoryPage() {
  const [entries, setEntries] = useState<HistoryEntry[]>([]);
  // localStorage is not available during SSR, so nothing renders until mount.
  const [ready, setReady] = useState(false);

  useEffect(() => {
    setEntries(getHistory());
    setReady(true);
  }, []);

  if (!ready) return null;

  if (entries.length === 0) {
    return (
      <div className="pt-16 text-center">
        <p className="text-sm text-muted">Nothing scanned yet.</p>
        <Link href="/" className="btn btn-primary mt-4">
          Scan a card
        </Link>
      </div>
    );
  }

  const totals = portfolioValue(entries);

  return (
    <div className="space-y-4">
      <section className="card-surface p-4">
        <p className="text-xs uppercase tracking-wide text-muted">
          Total across {totals.counted} {totals.counted === 1 ? "card" : "cards"}
        </p>
        <p className="text-3xl font-black tabular-nums text-accent">
          {formatMoney(totals.total, totals.currency as Currency)}
        </p>
        <p className="text-[11px] text-muted">
          Value at the time of each scan
          {totals.skipped > 0 && ` · ${totals.skipped} not counted (unpriced or other currency)`}
        </p>
      </section>

      <ul className="divide-y divide-line">
        {entries.map((entry) => (
          <li key={entry.id} className="flex items-center gap-3 py-2">
            <div className="relative h-16 w-11 shrink-0 overflow-hidden rounded bg-surface-2">
              <Image
                src={entry.imageUrl}
                alt={entry.cardName}
                fill
                sizes="44px"
                className="object-contain"
                unoptimized
              />
            </div>

            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{entry.cardName}</p>
              <p className="truncate text-xs text-muted">{entry.setName}</p>
              <p className="text-[10px] text-muted">
                {new Date(entry.scannedAt).toLocaleDateString()}
              </p>
            </div>

            <div className="shrink-0 text-right">
              <p className="text-sm font-semibold tabular-nums">
                {formatMoney(entry.valueAtScan, entry.currency)}
              </p>
              <button
                type="button"
                onClick={() => {
                  removeFromHistory(entry.id);
                  setEntries(getHistory());
                }}
                className="text-[10px] text-muted hover:text-bad"
              >
                Remove
              </button>
            </div>
          </li>
        ))}
      </ul>

      <button
        type="button"
        onClick={() => {
          if (window.confirm("Delete the whole scan history?")) {
            clearHistory();
            setEntries([]);
          }
        }}
        className="btn btn-secondary w-full text-sm"
      >
        Clear history
      </button>
    </div>
  );
}
