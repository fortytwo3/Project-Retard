"use client";

import Image from "next/image";
import { useEffect, useState } from "react";

import type { Card } from "@/lib/types";

interface Props {
  onSelect: (card: Card) => void;
  /** Prefills the box with whatever OCR thought it saw. */
  initialTerm?: string;
}

/**
 * Search by name. The scanner is the main path, but OCR fails on damaged,
 * sleeved, and heavily foiled cards often enough that typing the name has to be
 * a first-class option rather than a hidden fallback.
 */
export function ManualSearch({ onSelect, initialTerm = "" }: Props) {
  const [term, setTerm] = useState(initialTerm);
  const [cards, setCards] = useState<Card[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const query = term.trim();
    if (query.length < 2) {
      setCards([]);
      setError(null);
      return;
    }

    const controller = new AbortController();
    // Debounced so typing does not fire a request per keystroke.
    const timer = setTimeout(async () => {
      setLoading(true);
      setError(null);
      try {
        const response = await fetch(`/api/search?q=${encodeURIComponent(query)}`, {
          signal: controller.signal,
        });
        const json = (await response.json()) as { cards?: Card[]; error?: string };
        setCards(json.cards ?? []);
        if (json.error) setError(json.error);
      } catch (err) {
        if (!controller.signal.aborted) {
          setError(err instanceof Error ? err.message : "Search failed.");
        }
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    }, 350);

    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [term]);

  return (
    <div className="space-y-3">
      <input
        type="search"
        value={term}
        onChange={(event) => setTerm(event.target.value)}
        placeholder="Search by card name, e.g. Charizard ex"
        // 16px keeps iOS Safari from zooming the viewport on focus.
        className="w-full rounded-xl border border-line bg-surface px-4 py-3 text-base outline-none placeholder:text-muted focus:border-accent/60"
      />

      {loading && <p className="text-xs text-muted">Searching…</p>}
      {error && <p className="text-xs text-bad">{error}</p>}

      {cards.length > 0 && (
        <ul className="divide-y divide-line">
          {cards.map((card) => (
            <li key={card.id}>
              <button
                type="button"
                onClick={() => onSelect(card)}
                className="flex w-full items-center gap-3 py-2 text-left hover:opacity-80"
              >
                <div className="relative h-14 w-10 shrink-0 overflow-hidden rounded bg-surface-2">
                  <Image
                    src={card.images.small}
                    alt={card.name}
                    fill
                    sizes="40px"
                    className="object-contain"
                    unoptimized
                  />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{card.name}</p>
                  <p className="truncate text-xs text-muted">
                    {card.setName} · #{card.number}
                    {card.printedTotal ? `/${card.printedTotal}` : ""}
                  </p>
                </div>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
