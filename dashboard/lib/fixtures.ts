import type { DashboardData } from "./types";

const RELAY_A = "0xA1b2C3d4E5f6A1b2C3d4E5f6A1b2C3d4E5f6A1b2";
const RELAY_B = "0xB2c3D4e5F6a7B2c3D4e5F6a7B2c3D4e5F6a7B2c3";

const now = Math.floor(Date.now() / 1000);
const epochDuration = 3600;

function epochStart(n: number) {
  return Math.floor(now / epochDuration) * epochDuration - n * epochDuration;
}

function vayu(amount: number): string {
  return String(BigInt(Math.round(amount * 1e6)) * BigInt(1e12));
}

export const FIXTURE_DATA: DashboardData = {
  epochss: {
    items: [
      { epochId: 494410, relay: RELAY_A, activeCells: 42,  totalReadings: 187, totalReward: vayu(1240.5), committedAt: epochStart(0),  ipfsStatus: "INGESTED", swept: true  },
      { epochId: 494409, relay: RELAY_A, activeCells: 39,  totalReadings: 161, totalReward: vayu(1075.2), committedAt: epochStart(1),  ipfsStatus: "INGESTED", swept: true  },
      { epochId: 494408, relay: RELAY_B, activeCells: 51,  totalReadings: 203, totalReward: vayu(1351.8), committedAt: epochStart(2),  ipfsStatus: "INGESTED", swept: false },
      { epochId: 494407, relay: RELAY_A, activeCells: 47,  totalReadings: 192, totalReward: vayu(1280.0), committedAt: epochStart(3),  ipfsStatus: "INGESTED", swept: false },
      { epochId: 494406, relay: RELAY_B, activeCells: 35,  totalReadings: 140, totalReward: vayu(933.4),  committedAt: epochStart(4),  ipfsStatus: "INGESTED", swept: false },
      { epochId: 494405, relay: RELAY_A, activeCells: 44,  totalReadings: 178, totalReward: vayu(1186.7), committedAt: epochStart(5),  ipfsStatus: "INGESTED", swept: false },
      { epochId: 494404, relay: RELAY_B, activeCells: 29,  totalReadings: 115, totalReward: vayu(766.3),  committedAt: epochStart(6),  ipfsStatus: "PENDING",  swept: false },
      { epochId: 494403, relay: RELAY_A, activeCells: 53,  totalReadings: 214, totalReward: vayu(1426.2), committedAt: epochStart(7),  ipfsStatus: "INGESTED", swept: false },
      { epochId: 494402, relay: RELAY_A, activeCells: 38,  totalReadings: 152, totalReward: vayu(1013.5), committedAt: epochStart(8),  ipfsStatus: "INGESTED", swept: false },
      { epochId: 494401, relay: RELAY_B, activeCells: 22,  totalReadings: 88,  totalReward: vayu(586.8),  committedAt: epochStart(9),  ipfsStatus: "FAILED",   swept: false },
      { epochId: 494400, relay: RELAY_A, activeCells: 48,  totalReadings: 196, totalReward: vayu(1306.8), committedAt: epochStart(10), ipfsStatus: "INGESTED", swept: false },
      { epochId: 494399, relay: RELAY_B, activeCells: 41,  totalReadings: 167, totalReward: vayu(1113.7), committedAt: epochStart(11), ipfsStatus: "INGESTED", swept: false },
      { epochId: 494398, relay: RELAY_A, activeCells: 36,  totalReadings: 144, totalReward: vayu(960.0),  committedAt: epochStart(12), ipfsStatus: "INGESTED", swept: false },
      { epochId: 494397, relay: RELAY_B, activeCells: 19,  totalReadings: 76,  totalReward: vayu(507.2),  committedAt: epochStart(13), ipfsStatus: "INGESTED", swept: false },
      { epochId: 494396, relay: RELAY_A, activeCells: 55,  totalReadings: 221, totalReward: vayu(1474.0), committedAt: epochStart(14), ipfsStatus: "INGESTED", swept: false },
    ],
  },
  reporterss: {
    items: [
      { address: "0x1111111111111111111111111111111111111111", stake: vayu(10000), totalReadings: 2840, totalRewards: vayu(8712.4), totalClaimed: vayu(7500.0), isSlashed: false, lastSeenEpoch: 494410 },
      { address: "0x2222222222222222222222222222222222222222", stake: vayu(7500),  totalReadings: 2105, totalRewards: vayu(6460.3), totalClaimed: vayu(6000.0), isSlashed: false, lastSeenEpoch: 494409 },
      { address: "0x3333333333333333333333333333333333333333", stake: vayu(5000),  totalReadings: 1640, totalRewards: vayu(5032.1), totalClaimed: vayu(4000.0), isSlashed: false, lastSeenEpoch: 494410 },
      { address: "0x4444444444444444444444444444444444444444", stake: vayu(5000),  totalReadings: 1420, totalRewards: vayu(4356.8), totalClaimed: vayu(4356.8), isSlashed: false, lastSeenEpoch: 494408 },
      { address: "0x5555555555555555555555555555555555555555", stake: vayu(2500),  totalReadings: 980,  totalRewards: vayu(3004.5), totalClaimed: vayu(2000.0), isSlashed: false, lastSeenEpoch: 494407 },
      { address: "0x6666666666666666666666666666666666666666", stake: vayu(2500),  totalReadings: 760,  totalRewards: vayu(2330.9), totalClaimed: vayu(2330.9), isSlashed: false, lastSeenEpoch: 494405 },
      { address: "0x7777777777777777777777777777777777777777", stake: vayu(1000),  totalReadings: 410,  totalRewards: vayu(1258.4), totalClaimed: vayu(0),      isSlashed: false, lastSeenEpoch: 494403 },
      { address: "0x8888888888888888888888888888888888888888", stake: vayu(1000),  totalReadings: 290,  totalRewards: vayu(889.6),  totalClaimed: vayu(500.0),  isSlashed: false, lastSeenEpoch: 494400 },
      { address: "0x9999999999999999999999999999999999999999", stake: vayu(500),   totalReadings: 120,  totalRewards: vayu(368.2),  totalClaimed: vayu(368.2),  isSlashed: true,  lastSeenEpoch: 494390 },
      { address: "0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", stake: vayu(500),   totalReadings: 55,   totalRewards: vayu(168.7),  totalClaimed: vayu(0),      isSlashed: false, lastSeenEpoch: null    },
    ],
  },
  relayss: {
    items: [
      { address: RELAY_A, stake: vayu(50000), isActive: true,  epochsCommitted: 312 },
      { address: RELAY_B, stake: vayu(50000), isActive: true,  epochsCommitted: 287 },
      { address: "0xC3d4E5f6A7b8C3d4E5f6A7b8C3d4E5f6A7b8C3d4", stake: vayu(25000), isActive: false, epochsCommitted: 91  },
    ],
  },
};
