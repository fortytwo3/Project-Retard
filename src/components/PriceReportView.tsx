"use client";

import Image from "next/image";

import { formatMoney } from "@/lib/money";
import type { PriceReport, ProviderResult } from "@/lib/types";
import { SoldListings } from "./SoldListings";

interface Props {
  report: PriceReport;
  onRefresh: () => void;
  refreshing: boolean;
}

const METHOD_BADGE: Record<string, { text: string; className: string }> = {
  api: { text: "API", className: "bg-accent-deep/25 text-accent-deep" },
  scrape: { text: "scraped", className: "bg-surface-2 text-muted" },
  none: { text: "off", className: "bg-surface-2 text-muted" },
};

export function PriceReportView({ report, onRefresh, refreshing }: Props) {
  const { card, consensus, results } = report;

  return (
    <div className="space-y-4">
      <section className="card-surface flex gap-4 p-4">
        <div className="relative h-36 w-26 shrink-0 overflow-hidden rounded-lg bg-surface-2">
          <Image
            src={card.images.large || card.images.small}
            alt={card.name}
            fill
            sizes="120px"
            className="object-contain"
            unoptimized
          />
        </div>

        <div className="min-w-0 flex-1">
          <h1 className="truncate text-lg font-bold">{card.name}</h1>
          <p className="truncate text-sm text-muted">{card.setName}</p>
          <p className="text-xs text-muted">
            #{card.number}
            {card.printedTotal ? `/${card.printedTotal}` : ""}
            {card.rarity ? ` · ${card.rarity}` : ""}
          </p>

          <p className="mt-3 text-3xl font-black tabular-nums text-accent">
            {formatMoney(consensus.amount, consensus.currency)}
          </p>
          <p className="text-[11px] leading-tight text-muted">{consensus.basis}</p>
        </div>
      </section>

      <div className="flex items-center justify-between">
        <p className="text-xs text-muted">
          Updated {new Date(report.generatedAt).toLocaleTimeString()}
        </p>
        <button
          type="button"
          onClick={onRefresh}
          disabled={refreshing}
          className="btn btn-secondary px-3 py-1.5 text-xs"
        >
          {refreshing ? "Refreshing…" : "Refresh prices"}
        </button>
      </div>

      {results.map((result) => (
        <ProviderCard key={result.providerId} result={result} />
      ))}
    </div>
  );
}

function ProviderCard({ result }: { result: ProviderResult }) {
  const badge = METHOD_BADGE[result.method] ?? METHOD_BADGE.none;
  const isSoldData = result.providerId === "ebay" && result.label.includes("Sold");

  return (
    <section className="card-surface p-4">
      <header className="mb-3 flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h2 className="truncate text-sm font-semibold">{result.label}</h2>
          <div className="mt-1 flex flex-wrap items-center gap-1.5">
            <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${badge.className}`}>
              {badge.text}
            </span>
            {result.cached && (
              <span className="rounded bg-surface-2 px-1.5 py-0.5 text-[10px] text-muted">
                cached
              </span>
            )}
          </div>
        </div>

        {result.sourceUrl && (
          <a
            href={result.sourceUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="shrink-0 text-xs text-accent hover:underline"
          >
            Open ↗
          </a>
        )}
      </header>

      {result.status === "ok" ? (
        <div className="space-y-3">
          {result.prices.length > 0 && (
            <ul className="divide-y divide-line">
              {result.prices.map((price) => (
                <li key={price.label} className="flex items-baseline justify-between gap-3 py-1.5">
                  <span className="min-w-0 text-xs text-muted">
                    <span className="truncate">{price.label}</span>
                    {price.note && (
                      <span className="ml-1.5 text-[10px] opacity-70">({price.note})</span>
                    )}
                  </span>
                  <span className="shrink-0 text-sm font-semibold tabular-nums">
                    {formatMoney(price.amount, price.currency)}
                  </span>
                </li>
              ))}
            </ul>
          )}

          {result.soldListings && result.soldListings.length > 0 && (
            <SoldListings
              listings={result.soldListings}
              summary={result.soldSummary}
              areCompletedSales={isSoldData}
            />
          )}
        </div>
      ) : (
        <p className="text-xs text-muted">
          {result.message ??
            (result.status === "empty"
              ? "No prices available for this card."
              : "This source could not be reached.")}
        </p>
      )}
    </section>
  );
}
