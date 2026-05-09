package protocol.vayu.relay.service.commit.aggregation;

import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds OpenZeppelin-compatible Merkle trees for the DATA and REWARD roots
 * that are committed on-chain via {@code commitEpoch()}.
 *
 * Leaf formats mirror VayuTypes.sol exactly:
 *
 *   DATA leaf  = keccak256(abi.encodePacked(
 *                  reporter(20), h3Index(8), epochId(4),
 *                  aqi(2), pm25(2), pm10(2), o3(2), no2(2), so2(2), co(2),
 *                  timestamp(4)))           — 52 bytes total
 *   Sort key   = keccak256(abi.encodePacked(reporter(20), h3Index(8)))
 *   Leaves sorted ascending by sort key before tree construction.
 *
 *   REWARD leaf = keccak256(abi.encodePacked(
 *                  reporter(20), epochId(4), h3Index(8), amount(32)))  — 64 bytes total
 *   Leaves sorted ascending by reporter address (lexicographic on bytes).
 *
 * Tree construction follows the OZ sorted-pair hashing scheme:
 *   parent = keccak256(min(left, right) ++ max(left, right))
 * Odd layers promote the last leaf unchanged.
 */
public final class EpochMerkleBuilder {

    private EpochMerkleBuilder() {
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DATA tree
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the DATA Merkle root from all accepted readings in one epoch.
     *
     * @return 32-byte root; all-zeros for an empty reading list
     */
    public static byte[] buildDataRoot(List<ReadingSubmissionRequest> readings) {
        if (readings == null || readings.isEmpty()) {
            return new byte[32];
        }

        // 1. Compute leaf and sort key for each reading
        record LeafEntry(byte[] sortKey, byte[] leaf) {}
        List<LeafEntry> entries = new ArrayList<>(readings.size());
        for (ReadingSubmissionRequest r : readings) {
            entries.add(new LeafEntry(dataLeafSortKey(r), dataLeaf(r)));
        }

        // 2. Sort ascending by sort key (lexicographic on bytes)
        entries.sort(Comparator.comparing(e -> e.sortKey(), EpochMerkleBuilder::compareBytes32));

        List<byte[]> leaves = new ArrayList<>(entries.size());
        for (LeafEntry e : entries) {
            leaves.add(e.leaf());
        }

        return buildRoot(leaves);
    }

    /** DATA leaf: keccak256(abi.encodePacked(reporter, h3Index, epochId, aqi, pm25, pm10, o3, no2, so2, co, timestamp)) */
    public static byte[] dataLeaf(ReadingSubmissionRequest r) {
        byte[] buf = new byte[52];
        int pos = 0;

        byte[] reporter = decodeAddress(r.reporter());
        System.arraycopy(reporter, 0, buf, pos, 20);
        pos += 20;

        writeUint64BE(buf, pos, parseH3Index(r.h3Index()));
        pos += 8;

        writeUint32BE(buf, pos, r.epochId().intValue());
        pos += 4;

        writeUint16BE(buf, pos, r.aqi());
        pos += 2;

        writeUint16BE(buf, pos, r.pm25());
        pos += 2;

        writeUint16BE(buf, pos, orZero(r.pm10()));
        pos += 2;

        writeUint16BE(buf, pos, orZero(r.o3()));
        pos += 2;

        writeUint16BE(buf, pos, orZero(r.no2()));
        pos += 2;

        writeUint16BE(buf, pos, orZero(r.so2()));
        pos += 2;

        writeUint16BE(buf, pos, orZero(r.co()));
        pos += 2;

        writeUint32BE(buf, pos, r.timestamp().intValue());

        return Hash.sha3(buf);
    }

    /** Sort key for a DATA leaf: keccak256(abi.encodePacked(reporter, h3Index)) */
    public static byte[] dataLeafSortKey(ReadingSubmissionRequest r) {
        byte[] buf = new byte[28];
        System.arraycopy(decodeAddress(r.reporter()), 0, buf, 0, 20);
        writeUint64BE(buf, 20, parseH3Index(r.h3Index()));
        return Hash.sha3(buf);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REWARD tree
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the REWARD Merkle root from the per-reporter reward allocations.
     *
     * @return 32-byte root; all-zeros if no rewards
     */
    public static byte[] buildRewardRoot(List<ReporterReward> rewards, long epochId) {
        if (rewards == null || rewards.isEmpty()) {
            return new byte[32];
        }

        // Sort leaves ascending by reporter address (lexicographic on 20 bytes)
        List<ReporterReward> sorted = new ArrayList<>(rewards);
        sorted.sort(Comparator.comparing(rr -> decodeAddress(rr.reporter()),
                EpochMerkleBuilder::compareAddressBytes));

        List<byte[]> leaves = new ArrayList<>(sorted.size());
        for (ReporterReward rr : sorted) {
            leaves.add(rewardLeaf(rr.reporter(), epochId, rr.h3IndexLong(), rr.amount()));
        }

        return buildRoot(leaves);
    }

    /** REWARD leaf: keccak256(abi.encodePacked(reporter, epochId, h3Index, amount)) */
    public static byte[] rewardLeaf(String reporter, long epochId, long h3IndexLong, BigInteger amount) {
        byte[] buf = new byte[64];
        int pos = 0;

        System.arraycopy(decodeAddress(reporter), 0, buf, pos, 20);
        pos += 20;

        writeUint32BE(buf, pos, (int) epochId);
        pos += 4;

        writeUint64BE(buf, pos, h3IndexLong);
        pos += 8;

        byte[] amountBytes = encodeUint256(amount);
        System.arraycopy(amountBytes, 0, buf, pos, 32);

        return Hash.sha3(buf);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tree construction (OZ sorted-pair hashing)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a Merkle root using OpenZeppelin's sorted-pair hashing:
     *   parent = keccak256(min(a, b) ++ max(a, b))
     * Odd-length layers promote the last node unchanged.
     */
    static byte[] buildRoot(List<byte[]> leaves) {
        if (leaves.isEmpty()) {
            return new byte[32];
        }
        if (leaves.size() == 1) {
            return leaves.get(0);
        }

        List<byte[]> layer = new ArrayList<>(leaves);
        while (layer.size() > 1) {
            List<byte[]> next = new ArrayList<>();
            for (int i = 0; i < layer.size(); i += 2) {
                if (i + 1 < layer.size()) {
                    next.add(hashPair(layer.get(i), layer.get(i + 1)));
                } else {
                    // Odd node: promote unchanged
                    next.add(layer.get(i));
                }
            }
            layer = next;
        }
        return layer.get(0);
    }

    /** OZ sorted pair hash: keccak256(min(a,b) ++ max(a,b)) */
    static byte[] hashPair(byte[] a, byte[] b) {
        byte[] first;
        byte[] second;
        if (compareBytes32(a, b) <= 0) {
            first = a;
            second = b;
        } else {
            first = b;
            second = a;
        }
        byte[] combined = new byte[64];
        System.arraycopy(first, 0, combined, 0, 32);
        System.arraycopy(second, 0, combined, 32, 32);
        return Hash.sha3(combined);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Encoding helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Strips 0x prefix and returns 20 bytes for an Ethereum address. */
    static byte[] decodeAddress(String hex) {
        String clean = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        return Numeric.hexStringToByteArray(clean);
    }

    /** Parses a 0x-prefixed 16-char H3 index string to a uint64 value. */
    public static long parseH3Index(String hex) {
        String clean = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        return Long.parseUnsignedLong(clean, 16);
    }

    static void writeUint64BE(byte[] buf, int offset, long value) {
        for (int i = 7; i >= 0; i--) {
            buf[offset + i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
    }

    static void writeUint32BE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) ((value >>> 24) & 0xFF);
        buf[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        buf[offset + 2] = (byte) ((value >>>  8) & 0xFF);
        buf[offset + 3] = (byte) (value & 0xFF);
    }

    static void writeUint16BE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) ((value >>> 8) & 0xFF);
        buf[offset + 1] = (byte) (value & 0xFF);
    }

    /** Encodes a non-negative BigInteger as a 32-byte big-endian uint256. */
    static byte[] encodeUint256(BigInteger value) {
        byte[] raw = value.toByteArray(); // two's complement, may have leading 0x00
        byte[] result = new byte[32];
        int srcOffset = raw.length > 32 ? raw.length - 32 : 0;
        int dstOffset = 32 - Math.min(raw.length, 32);
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, srcOffset, result, dstOffset, length);
        return result;
    }

    static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Byte-array comparators
    // ─────────────────────────────────────────────────────────────────────────

    static int compareBytes32(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int diff = Byte.toUnsignedInt(a[i]) - Byte.toUnsignedInt(b[i]);
            if (diff != 0) return diff;
        }
        return a.length - b.length;
    }

    private static int compareAddressBytes(byte[] a, byte[] b) {
        for (int i = 0; i < 20; i++) {
            int diff = Byte.toUnsignedInt(a[i]) - Byte.toUnsignedInt(b[i]);
            if (diff != 0) return diff;
        }
        return 0;
    }
}
