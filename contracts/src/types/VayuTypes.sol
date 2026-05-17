// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @title VayuTypes
/// @notice Canonical type definitions, EIP-712 structs, and type hashes
///         for the Vayu AQI protocol. Every contract and off-chain component
///         (relay, fisherman, SDK) must use these exact definitions to produce
///         compatible Merkle leaves and EIP-712 digests.
library VayuTypes {

    // ─────────────────────────────────────────────────────────────────────────
    // EIP-712 Domain
    // ─────────────────────────────────────────────────────────────────────────

    /// @dev Domain separator fields. The settlement contract sets this at
    ///      initialization using block.chainid and its own address.
    ///      Changing any field invalidates all prior signatures.
    ///
    ///      Domain typehash (constant, never changes):
    ///      keccak256(
    ///        "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
    ///      )
    bytes32 public constant EIP712_DOMAIN_TYPEHASH =
        0x8b73c3c69bb8fe3d512ecc4cf759cc79239f7b179b0ffacaa9a75d522b39400f;

    string public constant DOMAIN_NAME    = "VayuProtocol";
    string public constant DOMAIN_VERSION = "1";

    // ─────────────────────────────────────────────────────────────────────────
    // AQI Reading Struct
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice A single AQI reading submitted by an edge device.
    ///
    ///   Mandatory fields  : reporter, h3Index, epochId, timestamp, aqi, pm25
    ///   Optional fields   : pm10, o3, no2, so2, co  (0 means "not measured")
    ///
    ///   Encoding notes:
    ///   - pm25 through co are stored as raw µg/m³ × 10 (one decimal place).
    ///     e.g. PM2.5 = 45.3 µg/m³ is stored as 453.
    ///     uint16 max (65535) → 6553.5 µg/m³, well above any real-world value.
    ///   - aqi is on the 0-500 AQI scale, fits in uint16.
    ///   - h3Index is the full 64-bit H3 cell index at resolution 8.
    ///   - epochId is a monotonically increasing counter (1 epoch = 1 hour).
    // forge-lint: disable-next-item(pascal-case-struct) — AQI is a domain acronym, AqiReading is less readable
    struct AQIReading {
        address reporter;   // 20 bytes — device Ethereum address
        uint64  h3Index;    //  8 bytes — H3 cell (resolution 8)
        uint32  epochId;    //  4 bytes — which epoch
        uint32  timestamp;  //  4 bytes — UNIX time of reading
        uint16  aqi;        //  2 bytes — composite AQI (MANDATORY, must > 0)
        uint16  pm25;       //  2 bytes — PM2.5 µg/m³ × 10 (MANDATORY, must > 0)
        uint16  pm10;       //  2 bytes — PM10  µg/m³ × 10 (0 = not measured)
        uint16  o3;         //  2 bytes — O₃    µg/m³ × 10 (0 = not measured)
        uint16  no2;        //  2 bytes — NO₂   µg/m³ × 10 (0 = not measured)
        uint16  so2;        //  2 bytes — SO₂   µg/m³ × 10 (0 = not measured)
        uint16  co;         //  2 bytes — CO    mg/m³  × 10 (0 = not measured)
    }
    // Total packed: 50 bytes

    /// @notice EIP-712 typehash for AQIReading.
    ///
    ///   keccak256(
    ///     "AQIReading("
    ///       "address reporter,"
    ///       "uint64 h3Index,"
    ///       "uint32 epochId,"
    ///       "uint32 timestamp,"
    ///       "uint16 aqi,"
    ///       "uint16 pm25,"
    ///       "uint16 pm10,"
    ///       "uint16 o3,"
    ///       "uint16 no2,"
    ///       "uint16 so2,"
    ///       "uint16 co"
    ///     ")"
    ///   )
    bytes32 public constant AQI_READING_TYPEHASH =
        keccak256(
            "AQIReading("
            "address reporter,"
            "uint64 h3Index,"
            "uint32 epochId,"
            "uint32 timestamp,"
            "uint16 aqi,"
            "uint16 pm25,"
            "uint16 pm10,"
            "uint16 o3,"
            "uint16 no2,"
            "uint16 so2,"
            "uint16 co"
            ")"
        );

    // ─────────────────────────────────────────────────────────────────────────
    // Merkle Leaf Formats
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Computes the DATA TREE leaf for a given reading.
    ///
    ///   leaf = keccak256(abi.encodePacked(
    ///     reporter, h3Index, epochId, aqi, pm25, pm10, o3, no2, so2, co, timestamp
    ///   ))
    ///
    ///   IMPORTANT: The reporter's EIP-712 signature is stored alongside the
    ///   reading in the IPFS blob but is NOT included in the leaf hash.
    ///   This keeps leaf computation deterministic and gas-efficient.
    ///   Signature authenticity is verified separately via ecrecover.
    ///
    ///   Leaf sort key (for deterministic tree construction):
    ///   keccak256(abi.encodePacked(reporter, h3Index))
    ///   Leaves must be sorted ascending by this key before tree construction.
    // forge-lint: disable-next-item(asm-keccak256) — readability over ~30 gas saving in rarely-called Merkle leaf computation
    function dataLeaf(AQIReading memory r) internal pure returns (bytes32) {
        return keccak256(abi.encodePacked(
            r.reporter,
            r.h3Index,
            r.epochId,
            r.aqi,
            r.pm25,
            r.pm10,
            r.o3,
            r.no2,
            r.so2,
            r.co,
            r.timestamp
        ));
    }

    /// @notice Computes the REWARD TREE leaf for a reporter's epoch payout.
    ///
    ///   leaf = keccak256(abi.encodePacked(
    ///     reporter, epochId, h3Index, amount
    ///   ))
    ///
    ///   One leaf per reporter per cell per epoch.
    ///   h3Index (cellId) is included to support future multi-cell reporting.
    ///   amount is in token wei (18 decimals).
    ///
    ///   Leaf sort key: reporter address (ascending).
    // forge-lint: disable-next-item(asm-keccak256) — readability over ~30 gas saving in rarely-called Merkle leaf computation
    function rewardLeaf(
        address reporter,
        uint32  epochId,
        uint64  h3Index,
        uint256 amount
    ) internal pure returns (bytes32) {
        return keccak256(abi.encodePacked(
            reporter,
            epochId,
            h3Index,
            amount
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EIP-712 Digest Computation
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Computes the EIP-712 struct hash for an AQIReading.
    ///         Used by the relay to verify reporter signatures, and by
    ///         fishermen during on-chain challenge verification.
    // forge-lint: disable-next-item(asm-keccak256) — readability over ~30 gas saving in EIP-712 struct hashing
    function hashReading(AQIReading memory r) internal pure returns (bytes32) {
        return keccak256(abi.encode(
            AQI_READING_TYPEHASH,
            r.reporter,
            r.h3Index,
            r.epochId,
            r.timestamp,
            r.aqi,
            r.pm25,
            r.pm10,
            r.o3,
            r.no2,
            r.so2,
            r.co
        ));
    }

    /// @notice Computes the full EIP-712 digest (ready for ecrecover).
    ///         domainSeparator is computed once by the settlement contract
    ///         at initialization and cached.
    // forge-lint: disable-next-item(asm-keccak256) — readability over ~30 gas saving in EIP-712 digest
    function toTypedDataHash(
        bytes32 domainSeparator,
        AQIReading memory r
    ) internal pure returns (bytes32) {
        return keccak256(abi.encodePacked(
            "\x19\x01",
            domainSeparator,
            hashReading(r)
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Epoch Commitment
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice On-chain record stored per committed epoch.
    struct EpochCommitment {
        bytes32 dataRoot;       // Merkle root of all readings
        bytes32 rewardRoot;     // Merkle root of all reward leaves
        string  ipfsCid;        // IPFS CID of the full epoch blob
        address relay;          // relay that committed this epoch
        uint64  committedAt;    // block.timestamp of commitEpoch() call
        uint32  totalReadings;  // informational — sum across all cells
        uint32  activeCells;    // cells with >= MIN_REPORTERS_PER_CELL
        bool    swept;          // true once expired unclaimed rewards swept
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Challenge Types
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Discriminator for challenge events.
    enum ChallengeType {
        SpatialAnomaly,         // cell median inconsistent with neighbours
        RewardComputation,      // relay computed rewards incorrectly
        DataIntegrity,          // data tree root doesn't match IPFS blob
        DuplicateLocation,      // same reporter in two distant cells, same epoch
        PenaltyListFraud        // relay wrongfully included reporter in penalty list
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Protocol Constants (tunable via governance in v2)
    // ─────────────────────────────────────────────────────────────────────────

    /// @dev 1 epoch = 1 hour in seconds.
    uint32 public constant EPOCH_DURATION = 1 hours;

    /// @dev Normal fisherman challenge window.
    uint32 public constant CHALLENGE_WINDOW = 12 hours;

    /// @dev Extended window for governance-triggered retroactive review.
    uint32 public constant GOVERNANCE_REVIEW_WINDOW = 30 days;

    /// @dev Reporters must claim within this window or rewards sweep to treasury.
    uint32 public constant CLAIM_EXPIRY = 90 days;

    /// @dev Minimum reporters in a cell for that cell to receive epoch rewards.
    uint8  public constant MIN_REPORTERS_PER_CELL = 3;

    /// @dev Consecutive zero-score epochs before reporter is auto-slashed.
    uint8  public constant CONSECUTIVE_ZERO_SCORES_THRESHOLD = 10;

    /// @dev Maximum H3 resolution accepted (resolution 8 = 0x08 in top nibble).
    uint8  public constant H3_RESOLUTION = 8;

    // Slash rates (basis points, denominator = BPS_DENOMINATOR)
    uint16 public constant BPS_DENOMINATOR = 10_000;

    /// @dev AQI difference threshold between a cell and its neighbours to qualify as anomalous.
    uint16 public constant SPATIAL_TOLERANCE_AQI = 50;

    uint16 public constant SLASH_REPORTER_CONSECUTIVE_ZEROS = 500;   //  5%
    uint16 public constant SLASH_REPORTER_FISHERMAN          = 2000;  // 20%
    uint16 public constant SLASH_REPORTER_DUPLICATE_LOCATION = 5000;  // 50%
    uint16 public constant SLASH_RELAY_DATA_INTEGRITY        = 3000;  // 30%
    uint16 public constant SLASH_RELAY_REWARD_COMPUTATION    = 3000;  // 30%
    uint16 public constant SLASH_RELAY_CENSORSHIP            = 2000;  // 20%
    uint16 public constant SLASH_RELAY_OFFLINE               = 500;   //  5%
    uint16 public constant SLASH_RELAY_PENALTY_LIST          = 3000;  // 30%

    /// @dev Fisherman receives this share of the slash pool (basis points).
    uint16 public constant FISHERMAN_SHARE = 5000; // 50%

    // Relay parameters
    uint16 public constant RELAY_FEE_BPS = 200; // 2% of epoch reward pool

    // ─────────────────────────────────────────────────────────────────────────
    // H3 Validation Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /// @notice Returns true if h3Index is a valid resolution-8 H3 cell.
    ///         H3 encodes resolution in bits 52-55 (0-indexed from LSB).
    ///         Resolution 8 = 0x8 in that nibble.
    function isValidH3Resolution8(uint64 h3Index) internal pure returns (bool) {
        // Bits 52-55: (h3Index >> 52) & 0xF == 8
        return ((h3Index >> 52) & 0xF) == H3_RESOLUTION;
    }
}
