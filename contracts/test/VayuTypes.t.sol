// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {VayuTypes} from "../src/types/VayuTypes.sol";

// ─────────────────────────────────────────────────────────────────────────────
// Harness
//
// VayuTypes functions are `internal pure`, so they can only be called from
// within another Solidity compilation unit that uses the library.  The harness
// exposes each function as `external` so the test contract can call them as
// normal EVM calls and Forge can measure gas per call site.
// ─────────────────────────────────────────────────────────────────────────────

contract VayuTypesHarness {
    using VayuTypes for VayuTypes.AQIReading;

    function dataLeaf(VayuTypes.AQIReading memory r) external pure returns (bytes32) {
        return VayuTypes.dataLeaf(r);
    }

    function rewardLeaf(
        address reporter,
        uint32  epochId,
        uint64  h3Index,
        uint256 amount
    ) external pure returns (bytes32) {
        return VayuTypes.rewardLeaf(reporter, epochId, h3Index, amount);
    }

    function hashReading(VayuTypes.AQIReading memory r) external pure returns (bytes32) {
        return VayuTypes.hashReading(r);
    }

    function toTypedDataHash(
        bytes32 domainSeparator,
        VayuTypes.AQIReading memory r
    ) external pure returns (bytes32) {
        return VayuTypes.toTypedDataHash(domainSeparator, r);
    }

    function isValidH3Resolution8(uint64 h3Index) external pure returns (bool) {
        return VayuTypes.isValidH3Resolution8(h3Index);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers shared across test groups
// ─────────────────────────────────────────────────────────────────────────────

contract VayuTypesTestBase is Test {
    VayuTypesHarness internal h;

    /// @dev A valid H3 resolution-8 index.
    ///      Bits 55-52 must equal 0x8.  The resolution nibble is the 3rd nibble
    ///      from the left (bits 55-52) of the 64-bit H3 index:
    ///        hex layout: [63-60][59-56][55-52][51-48]...
    ///                          0      0      8      0  ...
    ///      uint64(8) << 52 = 0x0080000000000000
    uint64 internal constant H3_RES8 = uint64(8) << 52;

    function setUp() public virtual {
        h = new VayuTypesHarness();
    }

    /// @dev Builds a fully-populated AQIReading fixture.
    function _fixture() internal pure returns (VayuTypes.AQIReading memory r) {
        r.reporter  = address(0xA11CE);
        r.h3Index   = H3_RES8;
        r.epochId   = 42;
        r.timestamp = 1_700_000_000;
        r.aqi       = 120;
        r.pm25      = 453;   // 45.3 µg/m³
        r.pm10      = 800;
        r.o3        = 300;
        r.no2       = 200;
        r.so2       = 100;
        r.co        = 50;
    }

    /// @dev Builds a minimal AQIReading (optional fields zeroed).
    function _minimal() internal pure returns (VayuTypes.AQIReading memory r) {
        r.reporter  = address(0xBEEF);
        r.h3Index   = H3_RES8;
        r.epochId   = 1;
        r.timestamp = 1;
        r.aqi       = 1;
        r.pm25      = 1;
        // pm10, o3, no2, so2, co remain 0
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Constants
// ─────────────────────────────────────────────────────────────────────────────

contract VayuTypes_Constants_Test is VayuTypesTestBase {

    // ── EIP-712 typehashes ────────────────────────────────────────────────────

    /// @dev The hardcoded EIP712_DOMAIN_TYPEHASH must equal the keccak256 of
    ///      the canonical EIP-712 domain type string.  Any deviation breaks all
    ///      cross-chain domain separators.
    function test_eip712DomainTypehash_matchesCanonicalString() public pure {
        bytes32 expected = keccak256(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
        );
        assertEq(VayuTypes.EIP712_DOMAIN_TYPEHASH, expected);
    }

    /// @dev AQI_READING_TYPEHASH is computed inline in the library from a
    ///      multi-line string literal — verify it round-trips correctly.
    ///      Field ORDER in the typehash must match the order used in hashReading().
    function test_aqiReadingTypehash_matchesCanonicalString() public pure {
        bytes32 expected = keccak256(
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
        assertEq(VayuTypes.AQI_READING_TYPEHASH, expected);
    }

    // ── Domain metadata ───────────────────────────────────────────────────────

    function test_domainName_isVayuProtocol() public pure {
        assertEq(VayuTypes.DOMAIN_NAME, "VayuProtocol");
    }

    function test_domainVersion_isOne() public pure {
        assertEq(VayuTypes.DOMAIN_VERSION, "1");
    }

    // ── Protocol constants (sanity / regression) ──────────────────────────────

    function test_epochDuration_isOneHour() public pure {
        assertEq(VayuTypes.EPOCH_DURATION, 1 hours);
    }

    function test_challengeWindow_is12Hours() public pure {
        assertEq(VayuTypes.CHALLENGE_WINDOW, 12 hours);
    }

    function test_governanceReviewWindow_is30Days() public pure {
        assertEq(VayuTypes.GOVERNANCE_REVIEW_WINDOW, 30 days);
    }

    function test_claimExpiry_is90Days() public pure {
        assertEq(VayuTypes.CLAIM_EXPIRY, 90 days);
    }

    function test_bpsDenominator_is10000() public pure {
        assertEq(VayuTypes.BPS_DENOMINATOR, 10_000);
    }

    /// @dev All slash rates must be > 0 and <= BPS_DENOMINATOR.
    function test_slashRates_withinValidBpsRange() public pure {
        uint16[8] memory rates = [
            VayuTypes.SLASH_REPORTER_CONSECUTIVE_ZEROS,
            VayuTypes.SLASH_REPORTER_FISHERMAN,
            VayuTypes.SLASH_REPORTER_DUPLICATE_LOCATION,
            VayuTypes.SLASH_RELAY_DATA_INTEGRITY,
            VayuTypes.SLASH_RELAY_REWARD_COMPUTATION,
            VayuTypes.SLASH_RELAY_CENSORSHIP,
            VayuTypes.SLASH_RELAY_OFFLINE,
            VayuTypes.SLASH_RELAY_PENALTY_LIST
        ];
        for (uint256 i; i < rates.length; ++i) {
            assertGt(rates[i], 0, "slash rate is zero");
            assertLe(rates[i], VayuTypes.BPS_DENOMINATOR, "slash rate exceeds 100%");
        }
    }

    function test_fishermanShare_is50Percent() public pure {
        assertEq(VayuTypes.FISHERMAN_SHARE, 5000);
    }

    function test_relayFeeBps_is2Percent() public pure {
        assertEq(VayuTypes.RELAY_FEE_BPS, 200);
    }

    function test_h3Resolution_is8() public pure {
        assertEq(VayuTypes.H3_RESOLUTION, 8);
    }

    function test_minReportersPerCell_is3() public pure {
        assertEq(VayuTypes.MIN_REPORTERS_PER_CELL, 3);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. dataLeaf
// ─────────────────────────────────────────────────────────────────────────────

contract VayuTypes_DataLeaf_Test is VayuTypesTestBase {

    /// @dev Known-vector: compute the expected leaf independently using raw
    ///      abi.encodePacked with the documented field order, then compare.
    ///      This catches any field re-ordering in the library implementation.
    function test_dataLeaf_knownVector_fullReading() public view {
        VayuTypes.AQIReading memory r = _fixture();

        bytes32 expected = keccak256(abi.encodePacked(
            r.reporter,   // address (20 bytes)
            r.h3Index,    // uint64  (8 bytes)
            r.epochId,    // uint32  (4 bytes)
            r.aqi,        // uint16  (2 bytes)
            r.pm25,       // uint16
            r.pm10,       // uint16
            r.o3,         // uint16
            r.no2,        // uint16
            r.so2,        // uint16
            r.co,         // uint16
            r.timestamp   // uint32  (4 bytes) — LAST per spec
        ));

        assertEq(h.dataLeaf(r), expected);
    }

    /// @dev Timestamp is the LAST field in the packed encoding (per the NatSpec
    ///      comment in VayuTypes).  Verify that swapping timestamp to position 3
    ///      (where it sits in the struct) produces a DIFFERENT leaf.
    ///      This explicitly documents the deliberate deviation from struct order.
    function test_dataLeaf_timestampIsLast_notAtStructPosition() public view {
        VayuTypes.AQIReading memory r = _fixture();

        // Naively pack in struct declaration order (wrong ordering)
        bytes32 wrongOrder = keccak256(abi.encodePacked(
            r.reporter,
            r.h3Index,
            r.epochId,
            r.timestamp, // ← at struct position, NOT last
            r.aqi,
            r.pm25,
            r.pm10,
            r.o3,
            r.no2,
            r.so2,
            r.co
        ));

        assertTrue(h.dataLeaf(r) != wrongOrder, "timestamp must be last in leaf");
    }

    /// @dev Minimal reading (optional fields zeroed) still produces a
    ///      deterministic, non-zero leaf.
    function test_dataLeaf_minimalReading_nonZero() public view {
        VayuTypes.AQIReading memory r = _minimal();
        bytes32 leaf = h.dataLeaf(r);
        assertTrue(leaf != bytes32(0));
    }

    /// @dev Two readings that differ only in one field produce different leaves.
    ///      Tested for every field to catch any field being accidentally omitted.
    ///
    ///      NOTE: Memory structs are reference types in Solidity — `b = a` makes
    ///      b point to the same underlying memory as a, so modifying b also
    ///      mutates a.  Each comparison therefore calls _fixture() independently.
    function test_dataLeaf_singleFieldDiff_producesDistinctLeaf() public view {
        bytes32 base = h.dataLeaf(_fixture());

        VayuTypes.AQIReading memory b;

        // reporter
        b = _fixture(); b.reporter = address(0xDEAD);
        assertTrue(h.dataLeaf(b) != base, "reporter diff not detected");

        // h3Index
        b = _fixture(); b.h3Index = H3_RES8 ^ 1;
        assertTrue(h.dataLeaf(b) != base, "h3Index diff not detected");

        // epochId
        b = _fixture(); b.epochId = _fixture().epochId + 1;
        assertTrue(h.dataLeaf(b) != base, "epochId diff not detected");

        // aqi
        b = _fixture(); b.aqi = _fixture().aqi + 1;
        assertTrue(h.dataLeaf(b) != base, "aqi diff not detected");

        // pm25
        b = _fixture(); b.pm25 = _fixture().pm25 + 1;
        assertTrue(h.dataLeaf(b) != base, "pm25 diff not detected");

        // pm10
        b = _fixture(); b.pm10 = _fixture().pm10 + 1;
        assertTrue(h.dataLeaf(b) != base, "pm10 diff not detected");

        // o3
        b = _fixture(); b.o3 = _fixture().o3 + 1;
        assertTrue(h.dataLeaf(b) != base, "o3 diff not detected");

        // no2
        b = _fixture(); b.no2 = _fixture().no2 + 1;
        assertTrue(h.dataLeaf(b) != base, "no2 diff not detected");

        // so2
        b = _fixture(); b.so2 = _fixture().so2 + 1;
        assertTrue(h.dataLeaf(b) != base, "so2 diff not detected");

        // co
        b = _fixture(); b.co = _fixture().co + 1;
        assertTrue(h.dataLeaf(b) != base, "co diff not detected");

        // timestamp
        b = _fixture(); b.timestamp = _fixture().timestamp + 1;
        assertTrue(h.dataLeaf(b) != base, "timestamp diff not detected");
    }

    /// @dev dataLeaf is a pure function — same input always produces same output.
    function test_dataLeaf_deterministic() public view {
        VayuTypes.AQIReading memory r = _fixture();
        assertEq(h.dataLeaf(r), h.dataLeaf(r));
    }

    /// @dev Fuzz: dataLeaf must never revert for any input.
    function test_fuzz_dataLeaf_neverReverts(
        address reporter,
        uint64  h3Index,
        uint32  epochId,
        uint32  timestamp,
        uint16  aqi,
        uint16  pm25,
        uint16  pm10,
        uint16  o3,
        uint16  no2,
        uint16  so2,
        uint16  co
    ) public view {
        VayuTypes.AQIReading memory r;
        r.reporter  = reporter;
        r.h3Index   = h3Index;
        r.epochId   = epochId;
        r.timestamp = timestamp;
        r.aqi       = aqi;
        r.pm25      = pm25;
        r.pm10      = pm10;
        r.o3        = o3;
        r.no2       = no2;
        r.so2       = so2;
        r.co        = co;
        h.dataLeaf(r); // must not revert
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. rewardLeaf
// ─────────────────────────────────────────────────────────────────────────────

contract VayuTypes_RewardLeaf_Test is VayuTypesTestBase {

    /// @dev Known-vector: expected leaf computed with documented field order.
    function test_rewardLeaf_knownVector() public view {
        address reporter = address(0xA11CE);
        uint32  epochId  = 7;
        uint64  h3Index  = H3_RES8;
        uint256 amount   = 1_000 * 1e18;

        bytes32 expected = keccak256(abi.encodePacked(
            reporter,
            epochId,
            h3Index,
            amount
        ));

        assertEq(h.rewardLeaf(reporter, epochId, h3Index, amount), expected);
    }

    /// @dev Two leaves that differ only in one field produce different hashes.
    ///      Catches any field being accidentally omitted or reordered.
    function test_rewardLeaf_singleFieldDiff_producesDistinctLeaf() public view {
        address reporter = address(0xA11CE);
        uint32  epochId  = 7;
        uint64  h3Index  = H3_RES8;
        uint256 amount   = 1_000 * 1e18;

        bytes32 base = h.rewardLeaf(reporter, epochId, h3Index, amount);

        assertTrue(h.rewardLeaf(address(0xDEAD), epochId, h3Index, amount) != base, "reporter");
        assertTrue(h.rewardLeaf(reporter, epochId + 1, h3Index, amount)    != base, "epochId");
        assertTrue(h.rewardLeaf(reporter, epochId, h3Index ^ 1, amount)    != base, "h3Index");
        assertTrue(h.rewardLeaf(reporter, epochId, h3Index, amount + 1)    != base, "amount");
    }

    /// @dev Zero amount is a valid leaf (e.g. reporter earned nothing but is
    ///      still recorded).  Must produce a non-zero hash.
    function test_rewardLeaf_zeroAmount_nonZeroLeaf() public view {
        bytes32 leaf = h.rewardLeaf(address(0xA11CE), 1, H3_RES8, 0);
        assertTrue(leaf != bytes32(0));
    }

    /// @dev Max uint256 amount does not overflow or revert.
    function test_rewardLeaf_maxAmount_doesNotRevert() public view {
        h.rewardLeaf(address(0xA11CE), 1, H3_RES8, type(uint256).max);
    }

    /// @dev Fuzz: rewardLeaf must never revert for any input.
    function test_fuzz_rewardLeaf_neverReverts(
        address reporter,
        uint32  epochId,
        uint64  h3Index,
        uint256 amount
    ) public view {
        h.rewardLeaf(reporter, epochId, h3Index, amount);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. hashReading  (EIP-712 struct hash)
// ─────────────────────────────────────────────────────────────────────────────

contract VayuTypes_HashReading_Test is VayuTypesTestBase {

    /// @dev Known-vector: re-encode with abi.encode in the documented order and
    ///      compare.  hashReading uses abi.encode (padded), NOT abi.encodePacked.
    ///
    ///      Field order in the encoding (from the NatSpec + type string):
    ///        typehash, reporter, h3Index, epochId, TIMESTAMP, aqi, pm25, pm10,
    ///        o3, no2, so2, co
    ///
    ///      Note: timestamp comes BEFORE aqi in abi.encode (matching the
    ///      EIP-712 type string order) but AFTER pm25/pm10/etc in dataLeaf.
    ///      These are intentionally different.
    function test_hashReading_knownVector() public view {
        VayuTypes.AQIReading memory r = _fixture();

        bytes32 expected = keccak256(abi.encode(
            VayuTypes.AQI_READING_TYPEHASH,
            r.reporter,
            r.h3Index,
            r.epochId,
            r.timestamp,   // ← 4th field, matching type string order
            r.aqi,
            r.pm25,
            r.pm10,
            r.o3,
            r.no2,
            r.so2,
            r.co
        ));

        assertEq(h.hashReading(r), expected);
    }

    /// @dev The struct hash must be distinct from the raw dataLeaf — they use
    ///      different encodings (abi.encode with typehash vs abi.encodePacked).
    function test_hashReading_distinctFromDataLeaf() public view {
        VayuTypes.AQIReading memory r = _fixture();
        assertTrue(h.hashReading(r) != h.dataLeaf(r));
    }

    /// @dev Single-field sensitivity: each field change must alter the hash.
    ///
    ///      NOTE: Memory structs are reference types in Solidity — `b = a` makes
    ///      b alias the same memory as a, so each comparison calls _fixture()
    ///      independently to obtain a true independent copy.
    function test_hashReading_singleFieldDiff_producesDistinctHash() public view {
        bytes32 base = h.hashReading(_fixture());

        VayuTypes.AQIReading memory b;

        b = _fixture(); b.reporter  = address(0xDEAD);
        assertTrue(h.hashReading(b) != base, "reporter");

        b = _fixture(); b.h3Index   = H3_RES8 ^ 1;
        assertTrue(h.hashReading(b) != base, "h3Index");

        b = _fixture(); b.epochId   = _fixture().epochId + 1;
        assertTrue(h.hashReading(b) != base, "epochId");

        b = _fixture(); b.timestamp = _fixture().timestamp + 1;
        assertTrue(h.hashReading(b) != base, "timestamp");

        b = _fixture(); b.aqi       = _fixture().aqi + 1;
        assertTrue(h.hashReading(b) != base, "aqi");

        b = _fixture(); b.pm25      = _fixture().pm25 + 1;
        assertTrue(h.hashReading(b) != base, "pm25");

        b = _fixture(); b.pm10      = _fixture().pm10 + 1;
        assertTrue(h.hashReading(b) != base, "pm10");

        b = _fixture(); b.o3        = _fixture().o3 + 1;
        assertTrue(h.hashReading(b) != base, "o3");

        b = _fixture(); b.no2       = _fixture().no2 + 1;
        assertTrue(h.hashReading(b) != base, "no2");

        b = _fixture(); b.so2       = _fixture().so2 + 1;
        assertTrue(h.hashReading(b) != base, "so2");

        b = _fixture(); b.co        = _fixture().co + 1;
        assertTrue(h.hashReading(b) != base, "co");
    }

    /// @dev Fuzz: hashReading must never revert for any input.
    function test_fuzz_hashReading_neverReverts(
        address reporter,
        uint64  h3Index,
        uint32  epochId,
        uint32  timestamp,
        uint16  aqi,
        uint16  pm25,
        uint16  pm10,
        uint16  o3,
        uint16  no2,
        uint16  so2,
        uint16  co
    ) public view {
        VayuTypes.AQIReading memory r;
        r.reporter  = reporter;
        r.h3Index   = h3Index;
        r.epochId   = epochId;
        r.timestamp = timestamp;
        r.aqi       = aqi;
        r.pm25      = pm25;
        r.pm10      = pm10;
        r.o3        = o3;
        r.no2       = no2;
        r.so2       = so2;
        r.co        = co;
        h.hashReading(r);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. toTypedDataHash — EIP-712 full digest + ecrecover round-trip
// ─────────────────────────────────────────────────────────────────────────────

contract VayuTypes_ToTypedDataHash_Test is VayuTypesTestBase {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /// @dev Builds a domain separator for an arbitrary verifying contract and
    ///      chain, mirroring what VayuEpochSettlement does at initialization.
    function _domainSeparator(address verifyingContract, uint256 chainId)
        internal
        pure
        returns (bytes32)
    {
        return keccak256(abi.encode(
            VayuTypes.EIP712_DOMAIN_TYPEHASH,
            keccak256(bytes(VayuTypes.DOMAIN_NAME)),
            keccak256(bytes(VayuTypes.DOMAIN_VERSION)),
            chainId,
            verifyingContract
        ));
    }

    // ── Known-vector ──────────────────────────────────────────────────────────

    /// @dev toTypedDataHash must produce the canonical "\x19\x01" prefixed hash.
    function test_toTypedDataHash_knownVector() public view {
        bytes32 domSep = _domainSeparator(address(0xC0FFEE), 1337);
        VayuTypes.AQIReading memory r = _fixture();

        bytes32 expected = keccak256(abi.encodePacked(
            "\x19\x01",
            domSep,
            h.hashReading(r)
        ));

        assertEq(h.toTypedDataHash(domSep, r), expected);
    }

    /// @dev Different domain separators produce different digests for the same
    ///      reading — chain isolation must hold.
    function test_toTypedDataHash_differentDomain_differentDigest() public view {
        VayuTypes.AQIReading memory r = _fixture();

        bytes32 domSep1 = _domainSeparator(address(0xAAAA), 1);
        bytes32 domSep2 = _domainSeparator(address(0xBBBB), 1);
        bytes32 domSep3 = _domainSeparator(address(0xAAAA), 137); // same contract, different chain

        assertTrue(h.toTypedDataHash(domSep1, r) != h.toTypedDataHash(domSep2, r),
            "different verifying contract must yield different digest");
        assertTrue(h.toTypedDataHash(domSep1, r) != h.toTypedDataHash(domSep3, r),
            "different chainId must yield different digest");
    }

    // ── ecrecover round-trip ──────────────────────────────────────────────────

    /// @dev THE MOST CRITICAL TEST in this suite.
    ///
    ///      Signs a digest with vm.sign() using a known private key, then
    ///      recovers the signer via ecrecover.  If any of the following are wrong,
    ///      recovery will produce address(0) or the wrong address:
    ///        - EIP712_DOMAIN_TYPEHASH
    ///        - AQI_READING_TYPEHASH
    ///        - hashReading field order
    ///        - "\x19\x01" prefix
    ///        - abi.encode vs abi.encodePacked selection
    function test_toTypedDataHash_ecrecoverRoundTrip() public view {
        uint256 privKey     = 0xA11CE_BEEF_CAFE_DEAD;
        address signerAddr  = vm.addr(privKey);

        bytes32 domSep = _domainSeparator(address(0x5E771E), 1);
        VayuTypes.AQIReading memory r = _fixture();
        r.reporter = signerAddr; // reporter signs their own reading

        bytes32 digest = h.toTypedDataHash(domSep, r);
        (uint8 v, bytes32 rs, bytes32 ss) = vm.sign(privKey, digest);

        address recovered = ecrecover(digest, v, rs, ss);
        assertEq(recovered, signerAddr, "ecrecover must return the signer");
    }

    /// @dev A corrupted reading (any field changed after signing) must fail
    ///      ecrecover — i.e., produce a different signer address.
    function test_toTypedDataHash_tamperedReading_ecrecoverFails() public view {
        uint256 privKey    = 0xA11CE_BEEF_CAFE_DEAD;
        address signerAddr = vm.addr(privKey);

        bytes32 domSep = _domainSeparator(address(0x5E771E), 1);
        VayuTypes.AQIReading memory r = _fixture();
        r.reporter = signerAddr;

        bytes32 digest = h.toTypedDataHash(domSep, r);
        (uint8 v, bytes32 rs, bytes32 ss) = vm.sign(privKey, digest);

        // Tamper: change the AQI value after signing
        r.aqi += 1;
        bytes32 tamperedDigest = h.toTypedDataHash(domSep, r);

        address recovered = ecrecover(tamperedDigest, v, rs, ss);
        assertTrue(recovered != signerAddr,
            "tampered reading must not recover to original signer");
    }

    /// @dev Fuzz: sign a random reading, recover, compare.
    ///      Proves round-trip integrity holds for arbitrary field values.
    function test_fuzz_toTypedDataHash_ecrecoverRoundTrip(
        uint64  privKey,
        uint64  h3Index,
        uint32  epochId,
        uint32  timestamp,
        uint16  aqi,
        uint16  pm25,
        uint16  pm10,
        uint16  o3,
        uint16  no2,
        uint16  so2,
        uint16  co
    ) public view {
        // vm.sign requires private key in range [1, secp256k1 order - 1]
        uint256 pk = bound(privKey, 1, type(uint64).max);
        address signer = vm.addr(pk);

        bytes32 domSep = _domainSeparator(address(0x5E771E), 1);

        VayuTypes.AQIReading memory r;
        r.reporter  = signer;
        r.h3Index   = h3Index;
        r.epochId   = epochId;
        r.timestamp = timestamp;
        r.aqi       = aqi;
        r.pm25      = pm25;
        r.pm10      = pm10;
        r.o3        = o3;
        r.no2       = no2;
        r.so2       = so2;
        r.co        = co;

        bytes32 digest = h.toTypedDataHash(domSep, r);
        (uint8 v, bytes32 rs, bytes32 ss) = vm.sign(pk, digest);

        assertEq(ecrecover(digest, v, rs, ss), signer);
    }

    /// @dev Fuzz: toTypedDataHash must never revert for any domain / reading.
    function test_fuzz_toTypedDataHash_neverReverts(
        bytes32 domainSeparator,
        address reporter,
        uint64  h3Index,
        uint32  epochId,
        uint32  timestamp,
        uint16  aqi,
        uint16  pm25,
        uint16  pm10,
        uint16  o3,
        uint16  no2,
        uint16  so2,
        uint16  co
    ) public view {
        VayuTypes.AQIReading memory r;
        r.reporter  = reporter;
        r.h3Index   = h3Index;
        r.epochId   = epochId;
        r.timestamp = timestamp;
        r.aqi       = aqi;
        r.pm25      = pm25;
        r.pm10      = pm10;
        r.o3        = o3;
        r.no2       = no2;
        r.so2       = so2;
        r.co        = co;
        h.toTypedDataHash(domainSeparator, r);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. isValidH3Resolution8
// ─────────────────────────────────────────────────────────────────────────────

contract VayuTypes_IsValidH3Resolution8_Test is VayuTypesTestBase {

    // ── Bit-layout reference ──────────────────────────────────────────────────
    //
    //   H3 cell index (64-bit):
    //     Bits 63-56 : high byte (mode + reserved)
    //     Bits 55-52 : resolution nibble  ← the nibble we check
    //     Bits 51-0  : cell coordinates
    //
    //   (h3Index >> 52) & 0xF == 8  →  resolution 8

    // ── Acceptance tests ──────────────────────────────────────────────────────

    /// @dev Synthetic index with resolution nibble = 8 must be accepted.
    function test_isValidH3Resolution8_res8_accepted() public view {
        // Force resolution nibble = 8: set bits 55-52 to 0x8
        uint64 res8 = (uint64(8) << 52);
        assertTrue(h.isValidH3Resolution8(res8));
    }

    /// @dev Resolution nibble = 8 with all other bits set must still pass.
    ///      Verifies the mask correctly isolates only bits 55-52.
    function test_isValidH3Resolution8_res8WithMaxOtherBits_accepted() public view {
        // Set all bits to 1, then force resolution nibble to exactly 8
        uint64 allOnes = type(uint64).max;
        // Clear bits 55-52, set to 8
        uint64 idx = (allOnes & ~(uint64(0xF) << 52)) | (uint64(8) << 52);
        assertTrue(h.isValidH3Resolution8(idx));
    }

    // ── Rejection tests ───────────────────────────────────────────────────────

    /// @dev All-ones index (resolution nibble = 0xF = 15) must be rejected.
    ///      Distinct from the sweep: verifies the mask isolates only bits 55-52
    ///      and does not misfire on other set bits.
    function test_isValidH3Resolution8_maxUint64_rejected() public view {
        assertFalse(h.isValidH3Resolution8(type(uint64).max));
    }

    // ── Exhaustive boundary sweep ─────────────────────────────────────────────

    /// @dev Sweep all 16 possible resolution values (0-15).
    ///      Exactly one (8) must return true; all others must return false.
    function test_isValidH3Resolution8_allResolutions_onlyRes8Accepted() public view {
        for (uint64 res = 0; res <= 15; ++res) {
            uint64 idx = (res << 52);
            if (res == 8) {
                assertTrue(h.isValidH3Resolution8(idx), "res 8 must be accepted");
            } else {
                assertFalse(h.isValidH3Resolution8(idx), "non-res-8 must be rejected");
            }
        }
    }

    // ── Fuzz ─────────────────────────────────────────────────────────────────

    /// @dev Fuzz: result must match the explicit inline bit-extract formula.
    ///      Also proves no-revert for arbitrary inputs (strictly stronger than a
    ///      dedicated no-revert fuzz).
    ///      This is an independent re-derivation of the same logic; if either
    ///      the library or the test has a typo, they'll disagree.
    function test_fuzz_isValidH3Resolution8_matchesInlineFormula(uint64 h3Index) public view {
        bool expected = ((h3Index >> 52) & 0xF) == 8;
        assertEq(h.isValidH3Resolution8(h3Index), expected);
    }
}
