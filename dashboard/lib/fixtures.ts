import type { CellData, DashboardData, EpochReadingRow, ReporterReadingRow } from "./types";

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
  challengess: {
    items: [
      { epochId: 494401, challenger: "0x5555555555555555555555555555555555555555", challengeType: "REWARD_COMPUTATION", succeeded: true,  txHash: "0xaabbcc1100000000000000000000000000000000000000000000000000000000" },
      { epochId: 494398, challenger: "0x6666666666666666666666666666666666666666", challengeType: "SPATIAL_ANOMALY",    succeeded: false, txHash: "0xaabbcc2200000000000000000000000000000000000000000000000000000000" },
      { epochId: 494405, challenger: "0x7777777777777777777777777777777777777777", challengeType: "DATA_INTEGRITY",      succeeded: null,  txHash: "0xaabbcc3300000000000000000000000000000000000000000000000000000000" },
    ],
  },
  slashess: {
    items: [
      { epochId: 494401, challengeType: "REWARD_COMPUTATION", offender: "0x9999999999999999999999999999999999999999", slashAmount: vayu(500), fishermanReward: vayu(50), txHash: "0xaabbcc1100000000000000000000000000000000000000000000000000000000" },
    ],
  },
};

// ── Fixture cell data for the mock H3 map ────────────────────────────────────
// Valid H3 resolution-8 cells covering key Bengaluru, India corridors.
//
// AQI gradient reflects real conditions (CPCB data, typical annual averages):
//   Lalbagh / Cubbon Park / Bannerghatta  →  Good      (parks, forests)
//   Yelahanka / JP Nagar / Electronic City →  Moderate  (outer suburbs, IT)
//   Whitefield / Koramangala / HSR         →  USG       (IT+residential mix)
//   Silk Board / Marathahalli / Domlur     →  Unhealthy (traffic bottlenecks)
//   Hebbal / Majestic / Rajajinagar        →  Unhealthy (highway junctions)
//   Peenya / Tumkur Rd industrial belt     →  Very Unhealthy (foundries, factories)
export const FIXTURE_CELLS: Record<number, CellData[]> = {
  494410: [
    // ── Green / low-pollution zones ──────────────────────────────────────────
    // Lalbagh Botanical Garden — large green cover, Good AQI
    { h3Index: "88618925bbfffff", medianAqi:  38, readingCount:  6, avgPm25:  9, avgPm10:  22, avgO3: 31, avgNo2:  15, avgSo2:  4, avgCo:  3 },
    // Cubbon Park — central urban forest, Good AQI
    { h3Index: "8860145b41fffff", medianAqi:  44, readingCount:  5, avgPm25: 12, avgPm10:  28, avgO3: 36, avgNo2:  19, avgSo2:  5, avgCo:  3 },
    // Bannerghatta — semi-rural / national park fringe, Good AQI
    { h3Index: "88618926a3fffff", medianAqi:  48, readingCount:  4, avgPm25: 13, avgPm10:  31, avgO3: 33, avgNo2:  14, avgSo2:  4, avgCo:  2 },
    // ── Outer suburbs / IT parks ─────────────────────────────────────────────
    // Yelahanka — northern suburb, low density, Moderate AQI
    { h3Index: "886016966bfffff", medianAqi:  58, readingCount:  7, avgPm25: 16, avgPm10:  42, avgO3: 38, avgNo2:  22, avgSo2:  6, avgCo:  4 },
    // JP Nagar — planned residential south, Moderate AQI
    { h3Index: "88618924abfffff", medianAqi:  72, readingCount:  8, avgPm25: 20, avgPm10:  52, avgO3: 44, avgNo2:  28, avgSo2:  7, avgCo:  5 },
    // Electronic City — IT SEZ, relatively contained traffic, Moderate AQI
    { h3Index: "886189266bfffff", medianAqi:  82, readingCount: 11, avgPm25: 24, avgPm10:  61, avgO3: 50, avgNo2:  32, avgSo2:  8, avgCo:  6 },
    // ── Residential / mixed-use ───────────────────────────────────────────────
    // Jayanagar — mature residential, tree-lined streets, Moderate AQI
    { h3Index: "8861892591fffff", medianAqi:  88, readingCount:  9, avgPm25: 26, avgPm10:  68, avgO3: 54, avgNo2:  36, avgSo2:  9, avgCo:  6 },
    // Koramangala — high-density urban mixed-use, Moderate–USG
    { h3Index: "88618925c5fffff", medianAqi:  96, readingCount: 13, avgPm25: 29, avgPm10:  74, avgO3: 58, avgNo2:  42, avgSo2: 10, avgCo:  7 },
    // HSR Layout — planned residential with commercial spine, USG
    { h3Index: "886189242dfffff", medianAqi: 103, readingCount: 10, avgPm25: 32, avgPm10:  81, avgO3: 61, avgNo2:  47, avgSo2: 11, avgCo:  8 },
    // Sarjapur Road — IT corridor with growing traffic, USG
    { h3Index: "8861892739fffff", medianAqi: 112, readingCount: 12, avgPm25: 35, avgPm10:  89, avgO3: 65, avgNo2:  53, avgSo2: 12, avgCo:  8 },
    // Whitefield — outer-ring IT hub, bus traffic heavy, USG
    { h3Index: "88618921ddfffff", medianAqi: 118, readingCount: 14, avgPm25: 37, avgPm10:  96, avgO3: 68, avgNo2:  58, avgSo2: 13, avgCo:  9 },
    // ── Traffic bottlenecks ───────────────────────────────────────────────────
    // Domlur — arterial connector, perpetual congestion, USG–Unhealthy
    { h3Index: "8861892537fffff", medianAqi: 126, readingCount:  9, avgPm25: 40, avgPm10: 104, avgO3: 71, avgNo2:  64, avgSo2: 14, avgCo: 10 },
    // Tin Factory — east Bengaluru junction, BMTC depot nearby, Unhealthy
    { h3Index: "8861892eedfffff", medianAqi: 135, readingCount:  8, avgPm25: 44, avgPm10: 112, avgO3: 74, avgNo2:  70, avgSo2: 16, avgCo: 11 },
    // Marathahalli Bridge — ORR bottleneck, peak-hour gridlock, Unhealthy
    { h3Index: "88618920b1fffff", medianAqi: 148, readingCount: 10, avgPm25: 49, avgPm10: 128, avgO3: 78, avgNo2:  79, avgSo2: 18, avgCo: 12 },
    // Silk Board Junction — worst traffic junction in India surveys, Unhealthy
    { h3Index: "88618925c9fffff", medianAqi: 158, readingCount:  7, avgPm25: 52, avgPm10: 138, avgO3: 82, avgNo2:  86, avgSo2: 20, avgCo: 13 },
    // Majestic / KR Market — dense commercial core, diesel buses, Unhealthy
    { h3Index: "8860145b55fffff", medianAqi: 163, readingCount:  8, avgPm25: 55, avgPm10: 145, avgO3: 85, avgNo2:  91, avgSo2: 22, avgCo: 14 },
    // Rajajinagar — near industrial belt, high vehicle load, Unhealthy
    { h3Index: "8860145b3bfffff", medianAqi: 172, readingCount:  6, avgPm25: 59, avgPm10: 155, avgO3: 88, avgNo2:  97, avgSo2: 26, avgCo: 15 },
    // Hebbal Flyover — NH44 interchange, trucks and commuters, Very Unhealthy
    { h3Index: "8861892c83fffff", medianAqi: 185, readingCount:  7, avgPm25: 64, avgPm10: 168, avgO3: 91, avgNo2: 105, avgSo2: 29, avgCo: 16 },
    // ── Industrial belt (Peenya / Tumkur Road) ────────────────────────────────
    // Tumkur Road Industrial — garment / chemical units, Very Unhealthy
    { h3Index: "88601459ebfffff", medianAqi: 198, readingCount:  5, avgPm25: 68, avgPm10: 182, avgO3: 94, avgNo2: 112, avgSo2: 34, avgCo: 18 },
    // Peenya Industrial Area — foundries, machine tools, electroplating, Very Unhealthy
    { h3Index: "886014591bfffff", medianAqi: 236, readingCount:  4, avgPm25: 82, avgPm10: 210, avgO3: 98, avgNo2: 128, avgSo2: 45, avgCo: 22 },
  ],
};

// ── Fixture readings per epoch ────────────────────────────────────────────────
// Individual sensor readings for epoch 494410, spread across Bengaluru zones.
export const FIXTURE_EPOCH_READINGS: Record<number, EpochReadingRow[]> = {
  494410: [
    // Green zones — reporters near parks
    { reporter: "0x1111111111111111111111111111111111111111", h3Index: "88618925bbfffff", timestamp: epochStart(0) +  0, aqi:  38, pm25:  9, pm10:  22, o3: 31, no2: 15, so2:  4, co:  3 },
    { reporter: "0x3333333333333333333333333333333333333333", h3Index: "8860145b41fffff", timestamp: epochStart(0) +  8, aqi:  44, pm25: 12, pm10:  28, o3: 36, no2: 19, so2:  5, co:  3 },
    { reporter: "0x2222222222222222222222222222222222222222", h3Index: "88618926a3fffff", timestamp: epochStart(0) + 14, aqi:  48, pm25: 13, pm10:  31, o3: 33, no2: 14, so2:  4, co:  2 },
    // Residential / IT corridor reporters
    { reporter: "0x1111111111111111111111111111111111111111", h3Index: "886016966bfffff", timestamp: epochStart(0) + 22, aqi:  58, pm25: 16, pm10:  42, o3: 38, no2: 22, so2:  6, co:  4 },
    { reporter: "0x4444444444444444444444444444444444444444", h3Index: "886189266bfffff", timestamp: epochStart(0) + 31, aqi:  82, pm25: 24, pm10:  61, o3: 50, no2: 32, so2:  8, co:  6 },
    { reporter: "0x2222222222222222222222222222222222222222", h3Index: "88618921ddfffff", timestamp: epochStart(0) + 40, aqi: 118, pm25: 37, pm10:  96, o3: 68, no2: 58, so2: 13, co:  9 },
    { reporter: "0x5555555555555555555555555555555555555555", h3Index: "8861892739fffff", timestamp: epochStart(0) + 47, aqi: 112, pm25: 35, pm10:  89, o3: 65, no2: 53, so2: 12, co:  8 },
    // Traffic corridors
    { reporter: "0x3333333333333333333333333333333333333333", h3Index: "88618920b1fffff", timestamp: epochStart(0) + 55, aqi: 148, pm25: 49, pm10: 128, o3: 78, no2: 79, so2: 18, co: 12 },
    { reporter: "0x1111111111111111111111111111111111111111", h3Index: "88618925c9fffff", timestamp: epochStart(0) + 63, aqi: 158, pm25: 52, pm10: 138, o3: 82, no2: 86, so2: 20, co: 13 },
    { reporter: "0x6666666666666666666666666666666666666666", h3Index: "8861892c83fffff", timestamp: epochStart(0) + 70, aqi: 185, pm25: 64, pm10: 168, o3: 91, no2: 105, so2: 29, co: 16 },
    // Industrial belt
    { reporter: "0x7777777777777777777777777777777777777777", h3Index: "88601459ebfffff", timestamp: epochStart(0) + 78, aqi: 198, pm25: 68, pm10: 182, o3: 94, no2: 112, so2: 34, co: 18 },
    { reporter: "0x2222222222222222222222222222222222222222", h3Index: "886014591bfffff", timestamp: epochStart(0) + 85, aqi: 236, pm25: 82, pm10: 210, o3: 98, no2: 128, so2: 45, co: 22 },
  ],
};

// ── Fixture readings per reporter ─────────────────────────────────────────────
// Multi-epoch reading history for the top two reporters across Bengaluru zones.
export const FIXTURE_REPORTER_READINGS: Record<string, ReporterReadingRow[]> = {
  // Reporter 0x1111 — roams widely: parks, residential, and traffic corridors
  "0x1111111111111111111111111111111111111111": [
    { epochId: 494410, h3Index: "88618925bbfffff", timestamp: epochStart(0) +  0, aqi:  38, pm25:  9, pm10:  22, o3: 31, no2: 15, so2:  4, co:  3 },
    { epochId: 494410, h3Index: "886016966bfffff", timestamp: epochStart(0) + 22, aqi:  58, pm25: 16, pm10:  42, o3: 38, no2: 22, so2:  6, co:  4 },
    { epochId: 494410, h3Index: "88618925c9fffff", timestamp: epochStart(0) + 63, aqi: 158, pm25: 52, pm10: 138, o3: 82, no2: 86, so2: 20, co: 13 },
    { epochId: 494409, h3Index: "88618925bbfffff", timestamp: epochStart(1) +  0, aqi:  41, pm25: 10, pm10:  24, o3: 32, no2: 16, so2:  4, co:  3 },
    { epochId: 494409, h3Index: "8861892591fffff", timestamp: epochStart(1) + 30, aqi:  92, pm25: 27, pm10:  71, o3: 56, no2: 38, so2:  9, co:  7 },
    { epochId: 494408, h3Index: "88618920b1fffff", timestamp: epochStart(2) + 10, aqi: 153, pm25: 51, pm10: 133, o3: 80, no2: 82, so2: 19, co: 12 },
    { epochId: 494407, h3Index: "886016966bfffff", timestamp: epochStart(3) +  5, aqi:  61, pm25: 17, pm10:  44, o3: 39, no2: 23, so2:  6, co:  4 },
    { epochId: 494406, h3Index: "88618925bbfffff", timestamp: epochStart(4) +  0, aqi:  35, pm25:  8, pm10:  20, o3: 29, no2: 13, so2:  3, co:  3 },
  ],
  // Reporter 0x2222 — focuses on IT corridor and industrial belt monitoring
  "0x2222222222222222222222222222222222222222": [
    { epochId: 494410, h3Index: "886189266bfffff", timestamp: epochStart(0) + 31, aqi:  82, pm25: 24, pm10:  61, o3: 50, no2: 32, so2:  8, co:  6 },
    { epochId: 494410, h3Index: "88618921ddfffff", timestamp: epochStart(0) + 40, aqi: 118, pm25: 37, pm10:  96, o3: 68, no2: 58, so2: 13, co:  9 },
    { epochId: 494410, h3Index: "886014591bfffff", timestamp: epochStart(0) + 85, aqi: 236, pm25: 82, pm10: 210, o3: 98, no2: 128, so2: 45, co: 22 },
    { epochId: 494409, h3Index: "886189266bfffff", timestamp: epochStart(1) + 31, aqi:  79, pm25: 23, pm10:  58, o3: 49, no2: 30, so2:  7, co:  6 },
    { epochId: 494409, h3Index: "88601459ebfffff", timestamp: epochStart(1) + 70, aqi: 204, pm25: 70, pm10: 188, o3: 96, no2: 116, so2: 36, co: 19 },
    { epochId: 494408, h3Index: "88618921ddfffff", timestamp: epochStart(2) + 40, aqi: 122, pm25: 38, pm10:  99, o3: 69, no2: 60, so2: 13, co:  9 },
    { epochId: 494407, h3Index: "886189266bfffff", timestamp: epochStart(3) + 31, aqi:  85, pm25: 25, pm10:  63, o3: 51, no2: 33, so2:  8, co:  6 },
  ],
};
