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
