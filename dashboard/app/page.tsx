import Link from "next/link";
import { fetchGraphQL } from "@/lib/ponder";
import { FIXTURE_DATA, FIXTURE_CELLS } from "@/lib/fixtures";
import type { DashboardData } from "@/lib/types";
import EpochHexMapLoader from "./components/EpochHexMapLoader";

const INDEXER_URL =
  process.env.NEXT_PUBLIC_INDEXER_URL ?? "http://localhost:42069";

export const revalidate = 60;

// ── GraphQL query ─────────────────────────────────────────────────────────────

const DASHBOARD_QUERY = `
  query DashboardData {
    epochss(limit: 15, orderBy: "committedAt", orderDirection: "desc") {
      items {
        epochId
        relay
        activeCells
        totalReadings
        totalReward
        committedAt
        ipfsStatus
        swept
      }
    }
    reporterss(limit: 20, orderBy: "stake", orderDirection: "desc") {
      items {
        address
        stake
        totalReadings
        totalRewards
        totalClaimed
        isSlashed
        lastSeenEpoch
      }
    }
    relayss(limit: 50) {
      items {
        address
        stake
        isActive
        epochsCommitted
      }
    }
    challengess(limit: 10, orderBy: "blockNumber", orderDirection: "desc") {
      items {
        epochId
        challenger
        challengeType
        succeeded
        txHash
      }
    }
    slashess(limit: 10, orderBy: "blockNumber", orderDirection: "desc") {
      items {
        epochId
        challengeType
        offender
        slashAmount
        fishermanReward
        txHash
      }
    }
  }
`;

// ── Helpers ───────────────────────────────────────────────────────────────────

function formatVayu(wei: string | null | undefined): string {
  if (wei == null) return "—";
  try {
    const vayu = Number(BigInt(wei)) / 1e18;
    return vayu.toLocaleString("en-US", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
  } catch {
    return "—";
  }
}

function subVayu(a: string | null | undefined, b: string | null | undefined): string {
  if (a == null || b == null) return "—";
  try {
    const diff = BigInt(a) - BigInt(b);
    const vayu = Number(diff < 0n ? 0n : diff) / 1e18;
    return vayu.toLocaleString("en-US", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
  } catch {
    return "—";
  }
}

function sumVayu(items: string[]): string {
  try {
    const total = items.reduce((acc, v) => acc + BigInt(v ?? "0"), 0n);
    const vayu = Number(total) / 1e18;
    return vayu.toLocaleString("en-US", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
  } catch {
    return "—";
  }
}

function truncateAddr(addr: string): string {
  return `${addr.slice(0, 6)}…${addr.slice(-4)}`;
}

function formatTime(unix: number): string {
  return new Date(unix * 1000).toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

const IPFS_BADGE: Record<string, string> = {
  INGESTED: "bg-emerald-500/15 text-emerald-400",
  PENDING: "bg-amber-500/15 text-amber-400",
  FAILED: "bg-red-500/15 text-red-400",
};

const CHALLENGE_TYPE_LABEL: Record<string, string> = {
  SPATIAL_ANOMALY:    "Spatial Anomaly",
  REWARD_COMPUTATION: "Reward Computation",
  DATA_INTEGRITY:     "Data Integrity",
  DUPLICATE_LOCATION: "Duplicate Location",
  PENALTY_LIST_FRAUD: "Penalty List Fraud",
};

// ── Page ──────────────────────────────────────────────────────────────────────

const MOCK = process.env.MOCK_DATA === "true";

export default async function DashboardPage() {
  let data: DashboardData | null = null;
  if (MOCK) {
    data = FIXTURE_DATA;
  } else {
    try {
      data = await fetchGraphQL<DashboardData>(DASHBOARD_QUERY);
    } catch {
      // Ponder not running or not yet synced — render empty state below
    }
  }

  const epochs = data?.epochss.items ?? [];
  const reporters = data?.reporterss.items ?? [];
  const relays = data?.relayss.items ?? [];
  const challenges = data?.challengess.items ?? [];
  const slashes = data?.slashess.items ?? [];

  const latestEpoch = epochs[0] ?? null;
  const activeRelays = relays.filter((r) => r.isActive).length;
  const totalReadings = epochs.reduce((acc, e) => acc + e.totalReadings, 0);
  const totalVayu = sumVayu(epochs.map((e) => e.totalReward));

  return (
    <main className="min-h-screen bg-zinc-950 text-zinc-100">
      {/* ── Header ─────────────────────────────────────────────────────────── */}
      <header className="border-b border-zinc-800 px-6 py-4 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-teal-400 text-xl font-bold">◆</span>
          <span className="text-base font-semibold tracking-tight">
            Vayu Protocol
          </span>
        </div>
        <span className="text-xs text-zinc-600">live · no-store fetch</span>
      </header>

      <div className="max-w-7xl mx-auto px-6 py-8 space-y-10">
        {/* ── Connection warning / mock banner ───────────────────────────────── */}
        {MOCK && (
          <div className="rounded-xl border border-sky-800/50 bg-sky-950/30 px-5 py-4 text-sm text-sky-400">
            Mock data active.{" "}
            <span className="text-sky-300">Remove <code className="font-mono">MOCK_DATA=true</code> from{" "}
            <code className="font-mono">.env.local</code> to connect to the live indexer.</span>
          </div>
        )}
        {!MOCK && data === null && (
          <div className="rounded-xl border border-amber-800/50 bg-amber-950/30 px-5 py-4 text-sm text-amber-400">
            Could not reach the Ponder indexer. Start it with{" "}
            <code className="font-mono text-amber-300">
              cd indexer &amp;&amp; ponder dev
            </code>{" "}
            and ensure{" "}
            <code className="font-mono text-amber-300">PONDER_URL</code> is set
            in <code className="font-mono text-amber-300">.env.local</code>.
          </div>
        )}

        {/* ── Stat cards ───────────────────────────────────────────────────── */}
        <section className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <StatCard
            label="Latest Epoch"
            value={latestEpoch ? `#${latestEpoch.epochId}` : "—"}
            sub={latestEpoch ? formatTime(latestEpoch.committedAt) : "no data yet"}
          />
          <StatCard
            label="Total Readings"
            value={totalReadings > 0 ? totalReadings.toLocaleString("en-US") : "0"}
            sub={epochs.length === 15 ? "across last 15 epochs" : `across ${epochs.length} epochs`}
          />
          <StatCard
            label="VAYU Distributed"
            value={totalVayu}
            sub={epochs.length === 15 ? "last 15 epochs" : "all indexed epochs"}
          />
          <StatCard
            label="Active Relays"
            value={String(activeRelays)}
            sub={`${relays.length} registered`}
          />
        </section>

        {/* ── H3 cell map ──────────────────────────────────────────────────── */}
        <section>
          <SectionHeading>Cell Air-Quality Map</SectionHeading>
          <EpochHexMapLoader
            epochs={epochs}
            indexerUrl={INDEXER_URL}
            mockCellsByEpoch={MOCK ? FIXTURE_CELLS : undefined}
          />
        </section>

        {/* ── Epochs table ─────────────────────────────────────────────────── */}
        <section>
          <SectionHeading>Recent Epochs</SectionHeading>
          {epochs.length === 0 ? (
            <EmptyState message="No epochs indexed yet." />
          ) : (
            <div className="overflow-x-auto rounded-xl border border-zinc-800">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-zinc-800 text-zinc-500 text-xs uppercase tracking-wider">
                    <Th>Epoch</Th>
                    <Th>Relay</Th>
                    <Th>Cells</Th>
                    <Th>Readings</Th>
                    <Th>Total Reward</Th>
                    <Th>Status</Th>
                    <Th>Committed</Th>
                  </tr>
                </thead>
                <tbody>
                  {epochs.map((e) => (
                    <tr
                      key={e.epochId}
                      className="border-b border-zinc-800/50 last:border-0 hover:bg-zinc-900/60 transition-colors"
                    >
                      <Td>
                        <Link
                          href={`/epochs/${e.epochId}`}
                          className="font-mono text-teal-400 hover:text-teal-300 hover:underline"
                        >
                          #{e.epochId}
                        </Link>
                      </Td>
                      <Td>
                        <span className="font-mono text-zinc-400">
                          {truncateAddr(e.relay)}
                        </span>
                      </Td>
                      <Td>{e.activeCells}</Td>
                      <Td>{e.totalReadings}</Td>
                      <Td>
                        <span className="font-mono">
                          {formatVayu(e.totalReward)} VAYU
                        </span>
                      </Td>
                      <Td>
                        <div className="flex items-center gap-1.5">
                          <span
                            className={`px-2 py-0.5 rounded-full text-xs font-medium ${IPFS_BADGE[e.ipfsStatus] ?? ""}`}
                          >
                            {e.ipfsStatus}
                          </span>
                          {e.swept && (
                            <span className="px-2 py-0.5 rounded-full text-xs font-medium bg-zinc-500/20 text-zinc-400">
                              Swept
                            </span>
                          )}
                        </div>
                      </Td>
                      <Td>
                        <span className="text-zinc-400">
                          {formatTime(e.committedAt)}
                        </span>
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        {/* ── Reporters table ──────────────────────────────────────────────── */}
        <section>
          <SectionHeading>Top Reporters</SectionHeading>
          {reporters.length === 0 ? (
            <EmptyState message="No reporters indexed yet." />
          ) : (
            <div className="overflow-x-auto rounded-xl border border-zinc-800">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-zinc-800 text-zinc-500 text-xs uppercase tracking-wider">
                    <Th>Address</Th>
                    <Th>Stake</Th>
                    <Th>Readings</Th>
                    <Th>Earned</Th>
                    <Th>Unclaimed</Th>
                    <Th>Last Epoch</Th>
                    <Th>Status</Th>
                  </tr>
                </thead>
                <tbody>
                  {reporters.map((r) => (
                    <tr
                      key={r.address}
                      className="border-b border-zinc-800/50 last:border-0 hover:bg-zinc-900/60 transition-colors"
                    >
                      <Td>
                        <span className="font-mono text-zinc-300">
                          {truncateAddr(r.address)}
                        </span>
                      </Td>
                      <Td>
                        <span className="font-mono">
                          {formatVayu(r.stake)} VAYU
                        </span>
                      </Td>
                      <Td>{r.totalReadings}</Td>
                      <Td>
                        <span className="font-mono">
                          {formatVayu(r.totalRewards)} VAYU
                        </span>
                      </Td>
                      <Td>
                        <span className="font-mono text-teal-400">
                          {subVayu(r.totalRewards, r.totalClaimed)} VAYU
                        </span>
                      </Td>
                      <Td>
                        {r.lastSeenEpoch != null ? `#${r.lastSeenEpoch}` : "—"}
                      </Td>
                      <Td>
                        {r.isSlashed ? (
                          <span className="px-2 py-0.5 rounded-full text-xs font-medium bg-red-500/15 text-red-400">
                            Slashed
                          </span>
                        ) : (
                          <span className="px-2 py-0.5 rounded-full text-xs font-medium bg-emerald-500/15 text-emerald-400">
                            Active
                          </span>
                        )}
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        {/* ── Challenges / Disputes ─────────────────────────────────────── */}
        <section>
          <SectionHeading>Challenges &amp; Disputes</SectionHeading>
          {challenges.length === 0 ? (
            <EmptyState message="No challenges indexed yet." />
          ) : (
            <div className="overflow-x-auto rounded-xl border border-zinc-800">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-zinc-800 text-zinc-500 text-xs uppercase tracking-wider">
                    <Th>Epoch</Th>
                    <Th>Type</Th>
                    <Th>Challenger</Th>
                    <Th>Outcome</Th>
                  </tr>
                </thead>
                <tbody>
                  {challenges.map((c) => (
                    <tr
                      key={`${c.epochId}-${c.challenger}-${c.challengeType}`}
                      className="border-b border-zinc-800/50 last:border-0 hover:bg-zinc-900/60 transition-colors"
                    >
                      <Td>
                        <Link
                          href={`/epochs/${c.epochId}`}
                          className="font-mono text-teal-400 hover:text-teal-300 hover:underline"
                        >
                          #{c.epochId}
                        </Link>
                      </Td>
                      <Td>{CHALLENGE_TYPE_LABEL[c.challengeType] ?? c.challengeType}</Td>
                      <Td>
                        <span className="font-mono text-zinc-400">
                          {truncateAddr(c.challenger)}
                        </span>
                      </Td>
                      <Td>
                        {c.succeeded === true && (
                          <span className="px-2 py-0.5 rounded-full text-xs font-medium bg-emerald-500/15 text-emerald-400">Upheld</span>
                        )}
                        {c.succeeded === false && (
                          <span className="px-2 py-0.5 rounded-full text-xs font-medium bg-red-500/15 text-red-400">Rejected</span>
                        )}
                        {c.succeeded === null && (
                          <span className="px-2 py-0.5 rounded-full text-xs font-medium bg-amber-500/15 text-amber-400">Pending</span>
                        )}
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {slashes.length > 0 && (
            <div className="mt-4 overflow-x-auto rounded-xl border border-zinc-800">
              <p className="text-xs text-zinc-500 px-4 pt-3 pb-2 border-b border-zinc-800">Recent Slashes</p>
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-zinc-800 text-zinc-500 text-xs uppercase tracking-wider">
                    <Th>Epoch</Th>
                    <Th>Type</Th>
                    <Th>Offender</Th>
                    <Th>Slash Amount</Th>
                    <Th>Fisherman Reward</Th>
                  </tr>
                </thead>
                <tbody>
                  {slashes.map((s) => (
                    <tr
                      key={`${s.epochId}-${s.challengeType}-${s.offender}`}
                      className="border-b border-zinc-800/50 last:border-0 hover:bg-zinc-900/60 transition-colors"
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

        {/* ── Relays table ─────────────────────────────────────────────────── */}
        <section>
          <SectionHeading>Registered Relays</SectionHeading>
          {relays.length === 0 ? (
            <EmptyState message="No relays indexed yet." />
          ) : (
            <div className="overflow-x-auto rounded-xl border border-zinc-800">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-zinc-800 text-zinc-500 text-xs uppercase tracking-wider">
                    <Th>Address</Th>
                    <Th>Stake</Th>
                    <Th>Epochs Committed</Th>
                    <Th>Status</Th>
                  </tr>
                </thead>
                <tbody>
                  {relays.map((r) => (
                    <tr
                      key={r.address}
                      className="border-b border-zinc-800/50 last:border-0 hover:bg-zinc-900/60 transition-colors"
                    >
                      <Td>
                        <span className="font-mono text-zinc-300">
                          {truncateAddr(r.address)}
                        </span>
                      </Td>
                      <Td>
                        <span className="font-mono">
                          {formatVayu(r.stake)} VAYU
                        </span>
                      </Td>
                      <Td>{r.epochsCommitted}</Td>
                      <Td>
                        {r.isActive ? (
                          <span className="px-2 py-0.5 rounded-full text-xs font-medium bg-emerald-500/15 text-emerald-400">
                            Active
                          </span>
                        ) : (
                          <span className="px-2 py-0.5 rounded-full text-xs font-medium bg-zinc-500/20 text-zinc-500">
                            Inactive
                          </span>
                        )}
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      </div>
    </main>
  );
}

// ── Sub-components ────────────────────────────────────────────────────────────

function StatCard({
  label,
  value,
  sub,
}: {
  label: string;
  value: string;
  sub: string;
}) {
  return (
    <div className="rounded-xl border border-zinc-800 bg-zinc-900/50 px-5 py-4">
      <p className="text-xs text-zinc-500 uppercase tracking-widest mb-1">
        {label}
      </p>
      <p className="text-2xl font-bold font-mono">{value}</p>
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
  return (
    <th className="px-4 py-3 text-left font-medium whitespace-nowrap">
      {children}
    </th>
  );
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
