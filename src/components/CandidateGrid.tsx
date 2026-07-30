"use client";

import Image from "next/image";

import type { Card, CardCandidate } from "@/lib/types";

interface Props {
  candidates: CardCandidate[];
  onSelect: (card: Card) => void;
}

/**
 * Ranked matches for a scan. Always a list, never an auto-pick: reprints mean
 * one name and number can legitimately belong to several cards worth wildly
 * different amounts, and guessing wrong is worse than one extra tap.
 */
export function CandidateGrid({ candidates, onSelect }: Props) {
  if (candidates.length === 0) return null;

  return (
    <div className="space-y-3">
      <h2 className="text-sm font-semibold text-muted">
        {candidates.length === 1 ? "1 match" : `${candidates.length} matches`} — pick the right
        printing
      </h2>

      <ul className="grid grid-cols-2 gap-3">
        {candidates.map(({ card, score, reasons }) => (
          <li key={card.id}>
            <button
              type="button"
              onClick={() => onSelect(card)}
              className="card-surface w-full overflow-hidden p-2 text-left transition-colors hover:border-accent/60"
            >
              <div className="relative mb-2 aspect-5/7 w-full overflow-hidden rounded-lg bg-surface-2">
                <Image
                  src={card.images.small}
                  alt={card.name}
                  fill
                  sizes="(max-width: 512px) 45vw, 220px"
                  className="object-contain"
                  unoptimized
                />
              </div>

              <p className="truncate text-sm font-semibold">{card.name}</p>
              <p className="truncate text-xs text-muted">{card.setName}</p>
              <p className="mt-1 text-xs text-muted">
                #{card.number}
                {card.printedTotal ? `/${card.printedTotal}` : ""}
                {card.rarity ? ` · ${card.rarity}` : ""}
              </p>

              <div className="mt-2 flex items-center gap-2">
                <div className="h-1 flex-1 overflow-hidden rounded-full bg-surface-2">
                  <div
                    className="h-full rounded-full bg-accent"
                    style={{ width: `${Math.round(score * 100)}%` }}
                  />
                </div>
                <span className="text-[10px] text-muted">{Math.round(score * 100)}%</span>
              </div>

              {reasons.length > 0 && (
                <p className="mt-1 truncate text-[10px] text-muted">{reasons[0]}</p>
              )}
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
