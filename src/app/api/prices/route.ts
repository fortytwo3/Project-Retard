import { NextResponse } from "next/server";

import { clearMemoryCache } from "@/lib/cache";
import { getCardById } from "@/lib/pokemontcg";
import { buildPriceReport } from "@/lib/providers";

export const runtime = "nodejs";

// Scraping eBay plus PriceCharting behind a per-host throttle can take a while
// on a cold cache.
export const maxDuration = 60;

export async function GET(request: Request) {
  const params = new URL(request.url).searchParams;
  const cardId = params.get("cardId")?.trim();

  if (!cardId) {
    return NextResponse.json({ error: "cardId is required." }, { status: 400 });
  }

  // The refresh button asks for live numbers rather than whatever is cached.
  if (params.get("refresh") === "true") {
    clearMemoryCache();
  }

  const card = await getCardById(cardId);
  if (!card) {
    return NextResponse.json({ error: `No card found with id "${cardId}".` }, { status: 404 });
  }

  try {
    const report = await buildPriceReport(card);
    return NextResponse.json(report);
  } catch (error) {
    return NextResponse.json(
      { error: error instanceof Error ? error.message : "Failed to build price report." },
      { status: 502 },
    );
  }
}
