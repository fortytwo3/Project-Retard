import { NextResponse } from "next/server";

import { searchCards } from "@/lib/pokemontcg";

export const runtime = "nodejs";

/** Free-text card search, backing the manual-entry fallback. */
export async function GET(request: Request) {
  const term = new URL(request.url).searchParams.get("q")?.trim() ?? "";

  if (term.length < 2) {
    return NextResponse.json({ cards: [] });
  }

  try {
    return NextResponse.json({ cards: await searchCards(term) });
  } catch (error) {
    return NextResponse.json(
      { cards: [], error: error instanceof Error ? error.message : "Search failed." },
      { status: 502 },
    );
  }
}
