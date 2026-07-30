import { NextResponse } from "next/server";

import { describeCapabilities } from "@/lib/env";

export const runtime = "nodejs";

/**
 * Which sources are live and how they are being read. Deliberately reports only
 * the *mode* each provider is in — never the key values themselves.
 */
export async function GET() {
  return NextResponse.json({ capabilities: describeCapabilities() });
}
