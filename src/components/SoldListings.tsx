"use client";

import { useState } from "react";

import { formatMoney } from "@/lib/money";
import type { SoldListing, SoldSummary } from "@/lib/types";

interface Props {
  listings: SoldListing[];
  summary?: SoldSummary;
  /** False when these are active asking prices rather than completed sales. */
  areCompletedSales: boolean;
}

const INITIAL_ROWS = 6;

function relativeDate(iso: string | null): string {
  if (!iso) return "";
  const days = Math.round((Date.now() - Date.parse(iso)) / 86_400_000);
  if (!Number.isFinite(days)) return "";
  if (days <= 0) return "today";
  if (days === 1) return "yesterday";
  if (days < 30) return `${days}d ago`;
  return `${Math.round(days / 30)}mo ago`;
}

export function SoldListings({ listings, summary, areCompletedSales }: Props) {
  const [expanded, setExpanded] = useState(false);

  if (listings.length === 0) return null;

  const visible = expanded ? listings : listings.slice(0, INITIAL_ROWS);

  return (
    <div className="space-y-3">
      {summary && (
        <div className="grid grid-cols-3 gap-2 text-center">
          <Stat label="Median" value={formatMoney(summary.median, summary.currency)} />
          <Stat
            label={areCompletedSales ? "Average" : "Avg asking"}
            value={formatMoney(summary.trimmedMean, summary.currency)}
            emphasis
          />
          <Stat
            label="Range"
            value={`${formatMoney(summary.min, summary.currency)}–${formatMoney(summary.max, summary.currency)}`}
            small
          />
        </div>
      )}

      {summary?.trendPct !== null && summary?.trendPct !== undefined && (
        <p className="text-center text-xs">
          <span className={summary.trendPct >= 0 ? "text-good" : "text-bad"}>
            {summary.trendPct >= 0 ? "▲" : "▼"} {Math.abs(summary.trendPct).toFixed(1)}%
          </span>{" "}
          <span className="text-muted">across this sample, oldest to newest</span>
        </p>
      )}

      <ul className="divide-y divide-line">
        {visible.map((listing, index) => (
          <li key={`${listing.url ?? listing.title}-${index}`} className="flex gap-3 py-2">
            <div className="min-w-0 flex-1">
              <a
                href={listing.url ?? undefined}
                target="_blank"
                rel="noopener noreferrer"
                className="line-clamp-2 text-xs leading-snug hover:text-accent"
              >
                {listing.title}
              </a>
              <p className="mt-0.5 flex flex-wrap items-center gap-x-2 text-[10px] text-muted">
                {listing.soldAt && <span>{relativeDate(listing.soldAt)}</span>}
                {listing.detectedGrade && (
                  <span className="rounded bg-surface-2 px-1.5 py-0.5">{listing.detectedGrade}</span>
                )}
                {listing.shippingIncluded && <span>free shipping</span>}
              </p>
            </div>

            <span className="shrink-0 text-sm font-semibold tabular-nums">
              {formatMoney(listing.price, listing.currency)}
            </span>
          </li>
        ))}
      </ul>

      {listings.length > INITIAL_ROWS && (
        <button
          type="button"
          onClick={() => setExpanded((open) => !open)}
          className="w-full text-xs text-muted hover:text-white"
        >
          {expanded ? "Show fewer" : `Show all ${listings.length}`}
        </button>
      )}
    </div>
  );
}

function Stat({
  label,
  value,
  emphasis,
  small,
}: {
  label: string;
  value: string;
  emphasis?: boolean;
  small?: boolean;
}) {
  return (
    <div className="rounded-lg bg-surface-2 px-2 py-2">
      <p className="text-[10px] uppercase tracking-wide text-muted">{label}</p>
      <p
        className={`tabular-nums ${small ? "text-[11px]" : "text-sm"} font-semibold ${emphasis ? "text-accent" : ""}`}
      >
        {value}
      </p>
    </div>
  );
}
