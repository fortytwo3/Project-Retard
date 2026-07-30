"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { CameraCapture } from "@/components/CameraCapture";
import { CandidateGrid } from "@/components/CandidateGrid";
import { ManualSearch } from "@/components/ManualSearch";
import { PriceReportView } from "@/components/PriceReportView";
import { addToHistory } from "@/lib/history";
import type { Card, CardCandidate, PriceReport, ScanParse } from "@/lib/types";

type Stage = "scan" | "reading" | "candidates" | "report";

export default function ScannerPage() {
  const [stage, setStage] = useState<Stage>("scan");
  const [status, setStatus] = useState("");
  const [error, setError] = useState<string | null>(null);

  const [preview, setPreview] = useState<string | null>(null);
  const [scan, setScan] = useState<ScanParse | null>(null);
  const [candidates, setCandidates] = useState<CardCandidate[]>([]);
  const [report, setReport] = useState<PriceReport | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  // Guards against a slow scan landing after the user has already reset.
  const runId = useRef(0);

  // Preload the OCR model in the background so the first scan is not the one
  // that pays for the download.
  useEffect(() => {
    let cancelled = false;
    import("@/lib/ocr/client").then(({ warmUpOcr }) => {
      if (!cancelled) void warmUpOcr();
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const reset = useCallback(() => {
    runId.current += 1;
    setStage("scan");
    setStatus("");
    setError(null);
    setPreview(null);
    setScan(null);
    setCandidates([]);
    setReport(null);
  }, []);

  const loadPrices = useCallback(async (card: Card, refresh = false) => {
    const run = ++runId.current;

    if (refresh) setRefreshing(true);
    else {
      setStage("reading");
      setStatus(`Checking prices for ${card.name}…`);
    }
    setError(null);

    try {
      const response = await fetch(
        `/api/prices?cardId=${encodeURIComponent(card.id)}${refresh ? "&refresh=true" : ""}`,
      );
      const json = (await response.json()) as PriceReport & { error?: string };

      if (run !== runId.current) return;

      if (!response.ok || json.error) {
        throw new Error(json.error ?? "Could not load prices.");
      }

      setReport(json);
      setStage("report");
      addToHistory(card, json);
    } catch (err) {
      if (run !== runId.current) return;
      setError(err instanceof Error ? err.message : "Could not load prices.");
      setStage(refresh ? "report" : "scan");
    } finally {
      if (run === runId.current) setRefreshing(false);
    }
  }, []);

  const handleCapture = useCallback(
    async (canvas: HTMLCanvasElement) => {
      const run = ++runId.current;

      setStage("reading");
      setError(null);
      setPreview(canvas.toDataURL("image/jpeg", 0.7));
      setStatus("Reading the card…");

      try {
        const { scanCardImage } = await import("@/lib/ocr/client");
        const parsed = await scanCardImage(canvas, (message) => {
          if (run === runId.current) setStatus(message);
        });

        if (run !== runId.current) return;
        setScan(parsed);
        setStatus("Looking up the card…");

        const response = await fetch("/api/identify", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ rawText: parsed.rawText, confidence: parsed.confidence }),
        });
        const json = (await response.json()) as {
          candidates?: CardCandidate[];
          message?: string;
          error?: string;
        };

        if (run !== runId.current) return;

        const found = json.candidates ?? [];
        setCandidates(found);

        // A single high-confidence hit is almost always right, and making the
        // user tap it adds nothing.
        if (found.length === 1 && found[0].score > 0.85) {
          await loadPrices(found[0].card);
          return;
        }

        setStage("candidates");
        if (found.length === 0) {
          setError(json.error ?? json.message ?? "No matching cards found.");
        }
      } catch (err) {
        if (run !== runId.current) return;
        setError(err instanceof Error ? err.message : "Scan failed.");
        setStage("candidates");
      }
    },
    [loadPrices],
  );

  return (
    <div className="space-y-4">
      {stage === "scan" && (
        <>
          <CameraCapture onCapture={handleCapture} />

          <details className="card-surface p-4">
            <summary className="cursor-pointer text-sm font-medium">
              Can&apos;t scan it? Search by name
            </summary>
            <div className="pt-3">
              <ManualSearch onSelect={(card) => loadPrices(card)} />
            </div>
          </details>
        </>
      )}

      {stage === "reading" && (
        <div className="space-y-4 pt-6 text-center">
          {preview && (
            // Data URL from the capture canvas — next/image adds nothing here.
            // eslint-disable-next-line @next/next/no-img-element
            <img
              src={preview}
              alt="Captured card"
              className="mx-auto max-h-72 rounded-xl border border-line"
            />
          )}
          <div className="mx-auto h-1 w-40 overflow-hidden rounded-full bg-surface-2">
            <div className="animate-sweep h-full w-1/4 rounded-full bg-accent" />
          </div>
          <p className="text-sm text-muted">{status}</p>
        </div>
      )}

      {stage === "candidates" && (
        <div className="space-y-4">
          <button type="button" onClick={reset} className="text-sm text-muted hover:text-white">
            ← Scan another
          </button>

          {scan && (
            <div className="card-surface space-y-1 p-4 text-xs text-muted">
              <p>
                Read as{" "}
                <span className="font-semibold text-white">
                  {scan.name ?? "unknown name"}
                  {scan.number ? ` · #${scan.number}` : ""}
                  {scan.printedTotal ? `/${scan.printedTotal}` : ""}
                </span>
              </p>
              <p>OCR confidence {Math.round(scan.confidence)}%</p>
            </div>
          )}

          {error && <p className="text-sm text-bad">{error}</p>}

          <CandidateGrid candidates={candidates} onSelect={(card) => loadPrices(card)} />

          <div className="card-surface p-4">
            <p className="mb-3 text-sm font-medium">
              {candidates.length > 0 ? "Not the right card?" : "Search by name instead"}
            </p>
            <ManualSearch
              onSelect={(card) => loadPrices(card)}
              initialTerm={scan?.name ?? ""}
            />
          </div>
        </div>
      )}

      {stage === "report" && report && (
        <div className="space-y-4">
          <button type="button" onClick={reset} className="text-sm text-muted hover:text-white">
            ← Scan another
          </button>

          {error && <p className="text-sm text-bad">{error}</p>}

          <PriceReportView
            report={report}
            refreshing={refreshing}
            onRefresh={() => loadPrices(report.card, true)}
          />
        </div>
      )}
    </div>
  );
}
