import { NextResponse } from "next/server";
import { z } from "zod";

import { parseScan } from "@/lib/ocr/parse";
import { identifyCard } from "@/lib/pokemontcg";
import type { ScanParse } from "@/lib/types";

export const runtime = "nodejs";

/**
 * Accepts either raw OCR text (the scanner path) or already-extracted fields
 * (the manual-correction path), so the user can fix a bad read without
 * re-photographing the card.
 */
const bodySchema = z.object({
  rawText: z.string().max(20_000).optional(),
  confidence: z.number().min(0).max(100).optional(),
  name: z.string().max(120).nullish(),
  number: z.string().max(20).nullish(),
  printedTotal: z.number().int().positive().nullish(),
  setCode: z.string().max(10).nullish(),
});

export async function POST(request: Request) {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: "Request body must be JSON." }, { status: 400 });
  }

  const parsed = bodySchema.safeParse(body);
  if (!parsed.success) {
    return NextResponse.json(
      { error: "Invalid request.", details: parsed.error.flatten() },
      { status: 400 },
    );
  }

  const input = parsed.data;

  // Start from the OCR text, then let any explicitly supplied field win.
  const base: ScanParse = input.rawText
    ? parseScan(input.rawText, input.confidence ?? 0)
    : {
        name: null,
        number: null,
        printedTotal: null,
        setCode: null,
        rawText: "",
        confidence: input.confidence ?? 0,
      };

  const scan: ScanParse = {
    ...base,
    name: input.name ?? base.name,
    number: input.number ?? base.number,
    printedTotal: input.printedTotal ?? base.printedTotal,
    setCode: input.setCode ?? base.setCode,
  };

  if (!scan.name && !scan.number) {
    return NextResponse.json({
      scan,
      candidates: [],
      message: "Could not read a card name or number. Try again with more light, or search by name.",
    });
  }

  try {
    const candidates = await identifyCard(scan);
    return NextResponse.json({
      scan,
      candidates,
      message: candidates.length === 0 ? "No matching cards found." : undefined,
    });
  } catch (error) {
    return NextResponse.json(
      {
        scan,
        candidates: [],
        error: error instanceof Error ? error.message : "Card lookup failed.",
      },
      { status: 502 },
    );
  }
}
