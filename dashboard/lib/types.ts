export interface Epoch {
  epochId: number;
  relay: string;
  activeCells: number;
  totalReadings: number;
  totalReward: string; // BigInt serialised as string by Ponder
  committedAt: number; // UNIX seconds
  ipfsStatus: "PENDING" | "INGESTED" | "FAILED";
  swept: boolean;
}

// Superset of Epoch — returned by the single-item GraphQL query
export interface EpochDetail extends Epoch {
  dataRoot: string;
  rewardRoot: string;
  ipfsCid: string;
  blockNumber: string;
  txHash: string;
  challengeWindowEnd: number;
}

export interface Reporter {
  address: string;
  stake: string;
  totalReadings: number;
  totalRewards: string;
  totalClaimed: string;
  isSlashed: boolean;
  lastSeenEpoch: number | null;
}

// Superset of Reporter — returned by the single-item GraphQL query
export interface ReporterDetail extends Reporter {
  staker: string | null;
  pendingUnstake: string;
  withdrawableAt: number | null;
  totalSlashed: string;
  firstSeenEpoch: number | null;
}

export interface Relay {
  address: string;
  stake: string;
  isActive: boolean;
  epochsCommitted: number;
}

export interface Challenge {
  epochId: number;
  challenger: string;
  challengeType: string;
  succeeded: boolean | null;
  txHash: string;
}

export interface Slash {
  epochId: number;
  challengeType: string;
  offender: string;
  slashAmount: string;
  fishermanReward: string;
  txHash: string;
}

export interface Claim {
  epochId: number;
  reporter: string;
  cellId: string;
  amount: string;
  claimedAt: number;
  txHash: string;
}

// Per-reading row from GET /epochs/:epochId/readings
export interface EpochReadingRow {
  reporter: string;
  h3Index: string;
  timestamp: number;
  aqi: number;
  pm25: number;
  pm10: number;
  o3: number;
  no2: number;
  so2: number;
  co: number;
}

// Per-reading row from GET /reporters/:address/readings
export interface ReporterReadingRow {
  epochId: number;
  h3Index: string;
  timestamp: number;
  aqi: number;
  pm25: number;
  pm10: number;
  o3: number;
  no2: number;
  so2: number;
  co: number;
}

export interface DashboardData {
  epochss: { items: Epoch[] };
  reporterss: { items: Reporter[] };
  relayss: { items: Relay[] };
  challengess: { items: Challenge[] };
  slashess: { items: Slash[] };
}

// Per-H3-cell aggregate returned by GET /epochs/:epochId/cells on the indexer.
export interface CellData {
  h3Index:      string; // H3 string form, e.g. "882830828dfffff"
  medianAqi:    number;
  readingCount: number;
  avgPm25:      number;
  avgPm10:      number;
  avgO3:        number;
  avgNo2:       number;
  avgSo2:       number;
  avgCo:        number;
}
