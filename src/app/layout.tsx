import type { Metadata, Viewport } from "next";
import Link from "next/link";

import "./globals.css";

export const metadata: Metadata = {
  title: "paladex-ex — Pokémon card price scanner",
  description:
    "Scan a Pokémon card and get live prices from TCGplayer, Cardmarket, PriceCharting and recent eBay sold listings.",
  manifest: "/manifest.webmanifest",
  appleWebApp: { capable: true, statusBarStyle: "black-translucent", title: "paladex-ex" },
};

export const viewport: Viewport = {
  themeColor: "#0b0d13",
  width: "device-width",
  initialScale: 1,
  // The scanner is a fixed-viewport camera UI; pinch-zoom just fights it.
  maximumScale: 1,
};

const NAV = [
  { href: "/", label: "Scan" },
  { href: "/history", label: "History" },
  { href: "/settings", label: "Sources" },
];

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="min-h-dvh antialiased">
        <div className="mx-auto flex min-h-dvh w-full max-w-lg flex-col">
          <header className="flex items-center justify-between px-4 py-3">
            <Link href="/" className="flex items-center gap-2">
              <span
                aria-hidden
                className="grid size-8 place-items-center rounded-full bg-accent text-base font-black text-ink"
              >
                ⌕
              </span>
              <span className="text-lg font-bold tracking-tight">
                paladex<span className="text-accent">·ex</span>
              </span>
            </Link>

            <nav className="flex gap-1 text-sm">
              {NAV.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  className="rounded-lg px-3 py-1.5 text-muted transition-colors hover:bg-surface-2 hover:text-white"
                >
                  {item.label}
                </Link>
              ))}
            </nav>
          </header>

          <main className="flex-1 px-4 pb-10">{children}</main>
        </div>
      </body>
    </html>
  );
}
