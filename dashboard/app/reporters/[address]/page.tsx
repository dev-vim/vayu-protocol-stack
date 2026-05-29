import Link from "next/link";
import { fetchGraphQL } from "@/lib/ponder";
import { FIXTURE_DATA, FIXTURE_REPORTER_READINGS } from "@/lib/fixtures";
import type {
  ReporterDetail,
  Slash,
  Claim,
  ReporterReadingRow,
} from "@/lib/types";

const INDEXER_URL =
  process.env.NEXT_PUBLIC_INDEXER_URL ?? "http://localhost:42069";

const MOCK = process.env.MOCK_DATA === "true";

// ── GraphQL ───────────────────────────────────────────────────────────────────

const REPORTER_DETAIL_QUERY = `
  query ReporterDetail($address: String!) {
    reporter: reporters(address: $address) {
      address staker stake pendingUnstake withdrawableAt
      totalReadings totalRewards totalClaimed totalSlashed isSlashed
      firstSeenEpoch lastSeenEpoch
    }
    slashes: slashess(where: { offender: $address }, limit: 50) {
      items {
        epochId challengeType slashAmount fishermanReward txHash
      }
    }
    claims: claimss(
      where: { reporter: $address }
      limit: 50
      orderBy: "claimedAt"
      orderDirection: "desc"
    ) {
      items {
        epochId cellId amount claimedAt txHash
      }
    }
  }
`;

interface ReporterDetailData {
  reporter: ReporterDetail | null;
  slashes: { items: Slash[] };
  claims:  { items: Claim[] };
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function formatVayu(wei: string | null | undefined): string {
  if (wei == null) return "—";
  try {
    const v = Number(BigInt(wei)) / 1e18;
    return v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  } catch { return "—"; }
}

function formatTime(unix: number): string {
  return new Date(unix * 1000).toLocaleString("en-US", {
    month: "short", day: "numeric",
    hour: "2-digit", minute: "2-digit", hour12: false,
  });
}

function truncateAddr(addr: string): string {
  return `${addr.slice(0, 6)}…${addr.slice(-4)}`;
}

function truncateHash(h: string): string {
  return `${h.slice(0, 10)}…${h.slice(-6)}`;
}

const CHALLENGE_TYPE_LABEL: Record<string, string> = {
  SPATIAL_ANOMALY:    "Spatial Anomaly",
  REWARD_COMPUTATION: "Reward Computation",
  DATA_INTEGRITY:     "Data Integrity",
  DUPLICATE_LOCATION: "Duplicate Location",
  PENALTY_LIST_FRAUD: "Penalty List Fraud",
};

// ── Page ──────────────────────────────────────────────────────────────────────

export default async function ReporterDetailPage({
  params,
}: {
  params: Promise<{ address: string }>;
}) {
  const { address: rawAddress } = await params;
  const address = rawAddress.toLowerCase();

  if (!/^0x[0-9a-f]{40}$/.test(address)) {
    return (
      <main className="min-h-screen bg-zinc-950 text-zinc-100 flex items-center justify-center">
        <p className="text-zinc-500">Invalid reporter address.</p>
      </main>
    );
  }

  let reporter: ReporterDetail | null = null;
  let slashes: Slash[] = [];
  let claims: Claim[] = [];
  let readings: ReporterReadingRow[] = [];

  if (MOCK) {
    const mockR = FIXTURE_DATA.reporterss.items.find(
      (r) => r.address.toLowerCase() === address,
    );
    if (mockR) {
      reporter = {
        ...mockR,
        staker:         mockR.address, // self-staked in mock
        pendingUnstake: "0",
        withdrawableAt: null,
        totalSlashed:   "0",
        firstSeenEpoch: mockR.lastSeenEpoch != null ? mockR.lastSeenEpoch - 10 : null,
      };
    }
    slashes  = FIXTURE_DATA.slashess.items.filter(
      (s) => s.offender.toLowerCase() === address,
    );
    readings = FIXTURE_REPORTER_READINGS[address] ?? [];
    // No claims in fixture data
  } else {
    const [gqlResult, readingsResult] = await Promise.allSettled([
      fetchGraphQL<ReporterDetailData>(REPORTER_DETAIL_QUERY, { address }),
      fetch(`${INDEXER_URL}/reporters/${address}/readings?limit=50`, {
        cache: "no-store",
      }).then((r) => r.json()),
    ]);

    if (gqlResult.status === "fulfilled") {
      reporter = gqlResult.value.reporter ?? null;
      slashes  = gqlResult.value.slashes.items;
      claims   = gqlResult.value.claims.items;
    }
    if (readingsResult.status === "fulfilled") {
      readings = (readingsResult.value as { readings?: ReporterReadingRow[] }).readings ?? [];
    }
  }

  // Group readings by epoch for the "per-epoch earnings" table
  const epochMap = new Map<number, { count: number; avgAqi: number }>();
  for (const r of readings) {
    const existing = epochMap.get(r.epochId);
    if (existing) {
      existing.count++;
      existing.avgAqi = Math.round((existing.avgAqi * (existing.count - 1) + r.aqi) / existing.count);
    } else {
      epochMap.set(r.epochId, { count: 1, avgAqi: r.aqi });
    }
  }
  const epochBreakdown = [...epochMap.entries()].sort((a, b) => b[0] - a[0]);

  return (
    <main className="min-h-screen bg-zinc-950 text-zinc-100">
      {/* ── Header ─────────────────────────────────────────────────────────── */}
      <header className="border-b border-zinc-800 px-6 py-4 flex items-center gap-3">
        <Link
          href="/"
          className="text-zinc-500 hover:text-zinc-300 text-sm transition-colors"
        >
          ← Dashboard
        </Link>
        <span className="text-zinc-700">/</span>
        <span className="font-mono text-zinc-300 text-sm">{address}</span>
        {reporter?.isSlashed && (
          <span className="ml-auto px-2 py-0.5 rounded-full text-xs font-medium bg-red-500/15 text-red-400">
            Slashed
          </span>
        )}
      </header>

      <div className="max-w-7xl mx-auto px-6 py-8 space-y-10">
        {reporter === null && (
          <div className="rounded-xl border border-amber-800/50 bg-amber-950/30 px-5 py-4 text-sm text-amber-400">
            Reporter {truncateAddr(address)} not found — they may not have staked yet.
          </div>
        )}

        {reporter !== null && (
          <>
            {/* ── Summary cards ────────────────────────────────────────────── */}
            <section className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <StatCard
                label="Stake"
                value={`${formatVayu(reporter.stake)} VAYU`}
                sub={reporter.pendingUnstake !== "0"
                  ? `${formatVayu(reporter.pendingUnstake)} unstaking`
                  : "no pending unstake"}
              />
              <StatCard
                label="Total Readings"
                value={String(reporter.totalReadings)}
                sub={reporter.firstSeenEpoch != null
                  ? `since epoch #${reporter.firstSeenEpoch}`
                  : ""}
              />
              <StatCard
                label="Total Earned"
                value={`${formatVayu(reporter.totalRewards)} VAYU`}
                sub="cumulative rewards"
              />
              <StatCard
                label="Unclaimed"
                value={`${formatVayu(
                  String(BigInt(reporter.totalRewards ?? "0") - BigInt(reporter.totalClaimed ?? "0"))
                )} VAYU`}
                sub={reporter.lastSeenEpoch != null
                  ? `last seen epoch #${reporter.lastSeenEpoch}`
                  : ""}
              />
            </section>

            {/* ── On-chain details ──────────────────────────────────────────── */}
            <section>
              <SectionHeading>Details</SectionHeading>
              <div className="rounded-xl border border-zinc-800 bg-zinc-900/40 divide-y divide-zinc-800 text-sm">
                {([
                  ["Address",         reporter.address],
                  ["Staker",          reporter.staker ?? "—"],
                  ["Pending Unstake", `${formatVayu(reporter.pendingUnstake)} VAYU`],
                  reporter.withdrawableAt != null
                    ? ["Withdrawable At", formatTime(reporter.withdrawableAt)]
                    : null,
                  ["Total Slashed",   `${formatVayu(reporter.totalSlashed)} VAYU`],
                  ["First Seen Epoch", reporter.firstSeenEpoch != null ? `#${reporter.firstSeenEpoch}` : "—"],
                  ["Last Seen Epoch",  reporter.lastSeenEpoch  != null ? `#${reporter.lastSeenEpoch}`  : "—"],
                ] as ([string, string] | null)[])
                  .filter((row): row is [string, string] => row !== null)
                  .map(([label, value]) => (
                    <div key={label} className="flex px-5 py-3 gap-4">
                      <span className="text-zinc-500 w-44 shrink-0">{label}</span>
                      <span className="font-mono text-zinc-300 break-all">{value}</span>
                    </div>
                  ))}
              </div>
            </section>
          </>
        )}

        {/* ── Per-epoch reading history ──────────────────────────────────────── */}
        <section>
          <SectionHeading>Epoch Reading History (last {readings.length} readings)</SectionHeading>
          {epochBreakdown.length === 0 ? (
            <EmptyState message="No per-reading history available yet." />
          ) : (
            <div className="overflow-x-auto rounded-xl border border-zinc-800">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-zinc-800 text-zinc-500 text-xs uppercase tracking-wider">
                    <Th>Epoch</Th>
                    <Th>Readings</Th>
                    <Th>Avg AQI</Th>
                  </tr>
                </thead>
                <tbody>
                  {epochBreakdown.map(([epochId, { count, avgAqi }]) => (
                    <tr
                      key={epochId}
                      className="border-b border-zinc-800/50 last:border-0 hover:bg-zinc-900/60"
                    >
                      <Td>
                        <Link
                          href={`/epochs/${epochId}`}
                          className="font-mono text-teal-400 hover:text-teal-300 hover:underline"
                        >
                          #{epochId}
                        </Link>
                      </Td>
                      <Td>{count}</Td>
                      <Td><AqiBadge aqi={avgAqi} /></Td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        {/* ── Recent claims ─────────────────────────────────────────────────── */}
        {claims.length > 0 && (
          <section>
            <SectionHeading>Recent Reward Claims</SectionHeading>
            <div className="overflow-x-auto rounded-xl border border-zinc-800">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-zinc-800 text-zinc-500 text-xs uppercase tracking-wider">
                    <Th>Epoch</Th>
                    <Th>Amount</Th>
                    <Th>Claimed At</Th>
                    <Th>Tx</Th>
                  </tr>
                </thead>
                <tbody>
                  {claims.map((c) => (
                    <tr
                      key={`${c.epochId}-${c.cellId}`}
                      className="border-b border-zinc-800/50 last:border-0 hover:bg-zinc-900/60"
                    >
                      <Td>
                        <Link
                          href={`/epochs/${c.epochId}`}
                          className="font-mono text-teal-400 hover:text-teal-300 hover:underline"
                        >
                          #{c.epochId}
                        </Link>
                      </Td>
                      <Td><span className="font-mono">{formatVayu(c.amount)} VAYU</span></Td>
                      <Td><span className="text-zinc-400">{formatTime(c.claimedAt)}</span></Td>
                      <Td><span className="font-mono text-zinc-600">{truncateHash(c.txHash)}</span></Td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        )}

        {/* ── Slash events ──────────────────────────────────────────────────── */}
        {slashes.length > 0 && (
          <section>
            <SectionHeading>Slash History</SectionHeading>
            <div className="overflow-x-auto rounded-xl border border-zinc-800">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-zinc-800 text-zinc-500 text-xs uppercase tracking-wider">
                    <Th>Epoch</Th>
                    <Th>Type</Th>
                    <Th>Slash Amount</Th>
                    <Th>Fisherman Reward</Th>
                    <Th>Tx</Th>
                  </tr>
                </thead>
                <tbody>
                  {slashes.map((s) => (
                    <tr
                      key={`${s.epochId}-${s.challengeType}`}
                      className="border-b border-zinc-800/50 last:border-0 hover:bg-zinc-900/60"
                    >
                      <Td>
                        <Link
                          href={`/epochs/${s.epochId}`}
                          className="font-mono text-teal-400 hover:text-teal-300 hover:underline"
                        >
                          #{s.epochId}
                        </Link>
                      </Td>
                      <Td>{CHALLENGE_TYPE_LABEL[s.challengeType] ?? s.challengeType}</Td>
                      <Td><span className="font-mono text-red-400">{formatVayu(s.slashAmount)} VAYU</span></Td>
                      <Td><span className="font-mono text-teal-400">{formatVayu(s.fishermanReward)} VAYU</span></Td>
                      <Td><span className="font-mono text-zinc-600">{truncateHash(s.txHash)}</span></Td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        )}
      </div>
    </main>
  );
}

// ── Sub-components ────────────────────────────────────────────────────────────

function StatCard({ label, value, sub }: { label: string; value: string; sub: string }) {
  return (
    <div className="rounded-xl border border-zinc-800 bg-zinc-900/50 px-5 py-4">
      <p className="text-xs text-zinc-500 uppercase tracking-widest mb-1">{label}</p>
      <p className="text-xl font-bold font-mono truncate">{value}</p>
      <p className="text-xs text-zinc-600 mt-1">{sub}</p>
    </div>
  );
}

function SectionHeading({ children }: { children: React.ReactNode }) {
  return (
    <h2 className="text-xs font-semibold text-zinc-500 uppercase tracking-widest mb-4">
      {children}
    </h2>
  );
}

function Th({ children }: { children: React.ReactNode }) {
  return <th className="px-4 py-3 text-left font-medium whitespace-nowrap">{children}</th>;
}

function Td({ children }: { children: React.ReactNode }) {
  return <td className="px-4 py-3 whitespace-nowrap">{children}</td>;
}

function EmptyState({ message }: { message: string }) {
  return (
    <div className="rounded-xl border border-zinc-800 border-dashed px-6 py-12 text-center">
      <p className="text-zinc-600 text-sm">{message}</p>
    </div>
  );
}

function AqiBadge({ aqi }: { aqi: number }) {
  let cls = "bg-emerald-500/15 text-emerald-400";
  if      (aqi > 200) cls = "bg-purple-500/15 text-purple-400";
  else if (aqi > 150) cls = "bg-red-500/15 text-red-400";
  else if (aqi > 100) cls = "bg-orange-500/15 text-orange-400";
  else if (aqi > 50)  cls = "bg-yellow-500/15 text-yellow-400";
  return <span className={`px-2 py-0.5 rounded-full text-xs font-mono font-medium ${cls}`}>{aqi}</span>;
}
