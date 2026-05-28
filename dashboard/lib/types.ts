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

export interface Reporter {
  address: string;
  stake: string;
  totalReadings: number;
  totalRewards: string;
  totalClaimed: string;
  isSlashed: boolean;
  lastSeenEpoch: number | null;
}

export interface Relay {
  address: string;
  stake: string;
  isActive: boolean;
  epochsCommitted: number;
}

export interface DashboardData {
  epochss: { items: Epoch[] };
  reporterss: { items: Reporter[] };
  relayss: { items: Relay[] };
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
