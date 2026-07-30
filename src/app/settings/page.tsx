import { describeCapabilities } from "@/lib/env";

export const dynamic = "force-dynamic";

const METHOD_LABEL = {
  api: "Official API",
  scrape: "Scraping the public page",
  none: "Off",
} as const;

/**
 * Shows which sources are live and why. Reads configuration on the server and
 * reports only the resulting mode — no key values are ever sent to the client.
 */
export default function SettingsPage() {
  const capabilities = describeCapabilities();

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-bold">Price sources</h1>
        <p className="mt-1 text-sm text-muted">
          Set keys in <code className="rounded bg-surface-2 px-1">.env.local</code> and restart the
          server. Sources without a key fall back to reading the public page.
        </p>
      </div>

      {capabilities.map((capability) => (
        <section key={capability.id} className="card-surface p-4">
          <div className="flex items-start justify-between gap-3">
            <h2 className="text-sm font-semibold">{capability.label}</h2>
            <span
              className={`shrink-0 rounded px-2 py-0.5 text-[10px] font-medium ${
                capability.active ? "bg-good/20 text-good" : "bg-surface-2 text-muted"
              }`}
            >
              {capability.active ? "active" : "off"}
            </span>
          </div>

          <p className="mt-1 text-xs text-accent-deep">{METHOD_LABEL[capability.method]}</p>
          <p className="mt-2 text-xs leading-relaxed text-muted">{capability.detail}</p>
        </section>
      ))}

      <section className="card-surface p-4">
        <h2 className="text-sm font-semibold">A note on the scrapers</h2>
        <p className="mt-2 text-xs leading-relaxed text-muted">
          When a source has no API key configured, the app reads the same public page you would open
          yourself. Requests are rate-limited to one per host at a time and cached, and scraped
          numbers are labelled as such in every report. Both sites restrict automated access in their
          terms of service, and either can change its markup without warning — if a source starts
          returning nothing, that is usually why. Set{" "}
          <code className="rounded bg-surface-2 px-1">SCRAPE_ENABLED=false</code> to use official
          APIs only.
        </p>
      </section>
    </div>
  );
}
