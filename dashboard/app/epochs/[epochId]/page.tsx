import Link from "next/link";
import { fetchGraphQL } from "@/lib/ponder";
import {
  FIXTURE_CELLS,
  FIXTURE_EPOCH_READINGS,
  FIXTURE_DATA,
} from "@/lib/fixtures";
import type {
  EpochDetail,
  Challenge,
  Slash,
  CellData,
  EpochReadingRow,
} from "@/lib/types";

const INDEXER_URL =
  process.env.NEXT_PUBLIC_INDEXER_URL ?? "http://localhost:42069";

const MOCK = process.env.MOCK_DATA === "true";

// ── GraphQL ───────────────────────────────────────────────────────────────────

const EPOCH_DETAIL_QUERY = `
  query EpochDetail($epochId: Int!) {
    epoch: epochs(epochId: $epochId) {
      epochId relay dataRoot rewardRoot ipfsCid
      activeCells totalReadings totalReward
      committedAt blockNumber txHash challengeWindowEnd swept ipfsStatus
    }
    challenges: challengess(where: { epochId: $epochId }, limit: 50) {
      items {
        epochId challenger challengeType succeeded txHash
      }
    }
    slashes: slashess(where: { epochId: $epochId }, limit: 50) {
      items {
        epochId challengeType offender slashAmount fishermanReward txHash
      }
    }
  }
`;

interface EpochDetailData {
  epoch: EpochDetail | null;
  challenges: { items: Challenge[] };
  slashes: { items: Slash[] };
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

const IPFS_BADGE: Record<string, string> = {
  INGESTED: "bg-emerald-500/15 text-emerald-400",
  PENDING:  "bg-amber-500/15 text-amber-400",
  FAILED:   "bg-red-500/15 text-red-400",
};

// ── Page ──────────────────────────────────────────────────────────────────────

export default async function EpochDetailPage({
  params,
}: {
  params: Promise<{ epochId: string }>;
}) {
  const { epochId: epochIdStr } = await params;
  const epochId = parseInt(epochIdStr, 10);

  if (isNaN(epochId) || epochId < 0) {
    return (
      <main className="min-h-screen bg-zinc-950 text-zinc-100 flex items-center justify-center">
        <p className="text-zinc-500">Invalid epoch ID.</p>
      </main>
    );
  }

  let epoch: EpochDetail | null = null;
  let challenges: Challenge[] = [];
  let slashes: Slash[] = [];
  let cells: CellData[] = [];
  let readings: EpochReadingRow[] = [];

  if (MOCK) {
    const mockEpoch = FIXTURE_DATA.epochss.items.find((e) => e.epochId === epochId);
    if (mockEpoch) {
      epoch = {
        ...mockEpoch,
        dataRoot:           "0x0000000000000000000000000000000000000000000000000000000000000001",
        rewardRoot:         "0x0000000000000000000000000000000000000000000000000000000000000002",
        ipfsCid:            "bafybeimock000000000000000000000000000000000000000000000000",
        blockNumber:        "12345678",
        txHash:             "0xdeadbeef00000000000000000000000000000000000000000000000000000000",
        challengeWindowEnd: mockEpoch.committedAt + 43200,
      };
    }
    challenges = FIXTURE_DATA.challengess.items.filter((c) => c.epochId === epochId);
    slashes    = FIXTURE_DATA.slashess.items.filter((s) => s.epochId === epochId);
    cells      = FIXTURE_CELLS[epochId] ?? [];
    readings   = FIXTURE_EPOCH_READINGS[epochId] ?? [];
  } else {
    // Fetch epoch + challenges/slashes from GraphQL, cells + readings from REST — all in parallel.
    const [gqlResult, cellsResult, readingsResult] = await Promise.allSettled([
      fetchGraphQL<EpochDetailData>(EPOCH_DETAIL_QUERY, { epochId }),
      fetch(`${INDEXER_URL}/epochs/${epochId}/cells`,    { cache: "no-store" }).then((r) => r.json()),
      fetch(`${INDEXER_URL}/epochs/${epochId}/readings`, { cache: "no-store" }).then((r) => r.json()),
    ]);

    if (gqlResult.status === "fulfilled") {
      epoch      = gqlResult.value.epoch ?? null;
      challenges = gqlResult.value.challenges.items;
      slashes    = gqlResult.value.slashes.items;
    }
    if (cellsResult.status === "fulfilled") {
      cells = (cellsResult.value as { cells?: CellData[] }).cells ?? [];
    }
    if (readingsResult.status === "fulfilled") {
      readings = (readingsResult.value as { readings?: EpochReadingRow[] }).readings ?? [];
    }
  }

  // Group readings by reporter to show per-reporter summary
  const reporterMap = new Map<string, number>();
  for (const r of readings) {
    reporterMap.set(r.reporter, (reporterMap.get(r.reporter) ?? 0) + 1);
  }
  const reporterBreakdown = [...reporterMap.entries()].sort((a, b) => b[1] - a[1]);

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
        <span className="text-base font-semibold font-mono text-teal-400">
          Epoch #{epochId}
        </span>
        {epoch && (
          <span
            className={`ml-auto px-2 py-0.5 rounded-full text-xs font-medium ${IPFS_BADGE[epoch.ipfsStatus] ?? ""}`}
          >
            {epoch.ipfsStatus}
          </span>
        )}
      </header>

      <div className="max-w-7xl mx-auto px-6 py-8 space-y-10">
        {epoch === null && (
          <div className="rounded-xl border border-amber-800/50 bg-amber-950/30 px-5 py-4 text-sm text-amber-400">
            Epoch #{epochId} not found — it may not have been indexed yet.
          </div>
        )}

        {epoch !== null && (
          <>
            {/* ── Summary cards ────────────────────────────────────────────── */}
            <section className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <StatCard label="Relay"        value={truncateAddr(epoch.relay)}       sub="committed by" />
              <StatCard label="Active Cells" value={String(epoch.activeCells)}       sub="H3 cells with ≥3 reporters" />
              <StatCard label="Readings"     value={String(epoch.totalReadings)}     sub="total across all cells" />
              <StatCard label="Total Reward" value={`${formatVayu(epoch.totalReward)} VAYU`} sub={epoch.swept ? "swept ✓" : "pending sweep"} />
            </section>

            {/* ── On-chain metadata ─────────────────────────────────────────── */}
            <section>
              <SectionHeading>On-Chain Info</SectionHeading>
              <div className="rounded-xl border border-zinc-800 bg-zinc-900/40 divide-y divide-zinc-800 text-sm">
                {[
                  ["Committed",         formatTime(epoch.committedAt)],
                  ["Challenge Window Ends", formatTime(epoch.challengeWindowEnd)],
                  ["Block",             epoch.blockNumber],
                  ["Tx Hash",           truncateHash(epoch.txHash)],
                  ["IPFS CID",          epoch.ipfsCid],
                  ["Data Root",         truncateHash(epoch.dataRoot)],
                  ["Reward Root",       truncateHash(epoch.rewardRoot)],
                ].map(([label, value]) => (
                  <div key={label} className="flex px-5 py-3 gap-4">
                    <span className="text-zinc-500 w-44 shrink-0">{label}</span>
                    <span className="font-mono text-zinc-300 break-all">{value}</span>
                  </div>
                ))}
              </div>
            </section>
          </>
        )}

        {/* ── Cell breakdown ────────────────────────────────────────────────── */}
        <section>
          <SectionHeading>Cell Breakdown ({cells.length} cells)</SectionHeading>
          {cells.length === 0 ? (
            <EmptyState message="No cell data available — IPFS ingestion may still be pending." />
          ) : (
            <div className="overflow-x-auto rounded-xl border border-zinc-800">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-zinc-800 text-zinc-500 text-xs uppercase tracking-wider">
                    <Th>H3 Index</Th>
                    <Th>Readings</Th>
                    <Th>Median AQI</Th>
                    <Th>PM2.5</Th>
                    <Th>PM10</Th>
                    <Th>O₃</Th>
                    <Th>NO₂</Th>
                  </tr>
                </thead>
                <tbody>
                  {cells.map((c) => (
                    <tr
                      key={c.h3Index}
                      className="border-b border-zinc-800/50 last:border-0 hover:bg-zinc-900/60"
                    >
                      <Td><span className="font-mono text-zinc-400">{c.h3Index}</span></Td>
                      <Td>{c.readingCount}</Td>
                      <Td><AqiBadge aqi={c.medianAqi} /></Td>
                      <Td>{c.avgPm25}</Td>
                      <Td>{c.avgPm10}</Td>
                      <Td>{c.avgO3}</Td>
                      <Td>{c.avgNo2}</Td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        {/* ── Reporter breakdown ────────────────────────────────────────────── */}
        <section>
          <SectionHeading>Reporter Breakdown ({reporterBreakdown.length} reporters)</SectionHeading>
          {reporterBreakdown.length === 0 ? (
            <EmptyState message="No per-reading data available — IPFS ingestion may still be pending." />
          ) : (
            <div className="overflow-x-auto rounded-xl border border-zinc-800">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-zinc-800 text-zinc-500 text-xs uppercase tracking-wider">
                    <Th>Reporter</Th>
                    <Th>Readings</Th>
                  </tr>
                </thead>
                <tbody>
                  {reporterBreakdown.map(([address, count]) => (
                    <tr
                      key={address}
                      className="border-b border-zinc-800/50 last:border-0 hover:bg-zinc-900/60"
                    >
                      <Td>
                        <Link
                          href={`/reporters/${address}`}
                          className="font-mono text-zinc-300 hover:text-teal-400 hover:underline"
                        >
                          {address}
                        </Link>
                      </Td>
                      <Td>{count}</Td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        {/* ── Challenges ────────────────────────────────────────────────────── */}
        {(challenges.length > 0 || slashes.length > 0) && (
          <section>
            <SectionHeading>Challenges &amp; Slashes</SectionHeading>
            {challenges.length > 0 && (
              <div className="overflow-x-auto rounded-xl border border-zinc-800 mb-4">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-zinc-800 text-zinc-500 text-xs uppercase tracking-wider">
                      <Th>Type</Th>
                      <Th>Challenger</Th>
                      <Th>Outcome</Th>
                      <Th>Tx</Th>
                    </tr>
                  </thead>
                  <tbody>
                    {challenges.map((c) => (
                      <tr
                        key={`${c.challenger}-${c.challengeType}`}
                        className="border-b border-zinc-800/50 last:border-0 hover:bg-zinc-900/60"
                      >
                        <Td>{CHALLENGE_TYPE_LABEL[c.challengeType] ?? c.challengeType}</Td>
                        <Td><span className="font-mono text-zinc-400">{truncateAddr(c.challenger)}</span></Td>
                        <Td>
                          {c.succeeded === true  && <Badge color="green">Upheld</Badge>}
                          {c.succeeded === false && <Badge color="red">Rejected</Badge>}
                          {c.succeeded === null  && <Badge color="amber">Pending</Badge>}
                        </Td>
                        <Td><span className="font-mono text-zinc-600">{truncateHash(c.txHash)}</span></Td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {slashes.length > 0 && (
              <div className="overflow-x-auto rounded-xl border border-zinc-800">
                <p className="text-xs text-zinc-500 px-4 pt-3 pb-2 border-b border-zinc-800">Slashes</p>
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-zinc-800 text-zinc-500 text-xs uppercase tracking-wider">
                      <Th>Type</Th>
                      <Th>Offender</Th>
                      <Th>Slash Amount</Th>
                      <Th>Fisherman Reward</Th>
                    </tr>
                  </thead>
                  <tbody>
                    {slashes.map((s) => (
                      <tr
                        key={`${s.challengeType}-${s.offender}`}
                        className="border-b border-zinc-800/50 last:border-0 hover:bg-zinc-900/60"
                      >
                        <Td>{CHALLENGE_TYPE_LABEL[s.challengeType] ?? s.challengeType}</Td>
                        <Td>
                          <Link
                            href={`/reporters/${s.offender}`}
                            className="font-mono text-red-400 hover:underline"
                          >
                            {truncateAddr(s.offender)}
                          </Link>
                        </Td>
                        <Td><span className="font-mono">{formatVayu(s.slashAmount)} VAYU</span></Td>
                        <Td><span className="font-mono text-teal-400">{formatVayu(s.fishermanReward)} VAYU</span></Td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
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

function Badge({ color, children }: { color: "green" | "red" | "amber"; children: React.ReactNode }) {
  const cls = {
    green: "bg-emerald-500/15 text-emerald-400",
    red:   "bg-red-500/15 text-red-400",
    amber: "bg-amber-500/15 text-amber-400",
  }[color];
  return <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${cls}`}>{children}</span>;
}

function AqiBadge({ aqi }: { aqi: number }) {
  let cls = "bg-emerald-500/15 text-emerald-400";
  if      (aqi > 200) cls = "bg-purple-500/15 text-purple-400";
  else if (aqi > 150) cls = "bg-red-500/15 text-red-400";
  else if (aqi > 100) cls = "bg-orange-500/15 text-orange-400";
  else if (aqi > 50)  cls = "bg-yellow-500/15 text-yellow-400";
  return <span className={`px-2 py-0.5 rounded-full text-xs font-mono font-medium ${cls}`}>{aqi}</span>;
}
