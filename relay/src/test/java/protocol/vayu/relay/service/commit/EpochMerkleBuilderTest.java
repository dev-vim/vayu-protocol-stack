package protocol.vayu.relay.service.commit;

import org.junit.jupiter.api.Test;
import org.web3j.utils.Numeric;
import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EpochMerkleBuilderTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Encoding helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void decodeAddressShouldReturn20Bytes() {
        byte[] addr = EpochMerkleBuilder.decodeAddress("0x1111111111111111111111111111111111111111");
        assertEquals(20, addr.length);
        for (byte b : addr) assertEquals(0x11, Byte.toUnsignedInt(b));
    }

    @Test
    void parseH3IndexShouldRoundTrip() {
        long h3 = EpochMerkleBuilder.parseH3Index("0x0882830a1fffffff");
        byte[] buf = new byte[8];
        EpochMerkleBuilder.writeUint64BE(buf, 0, h3);
        assertEquals("0882830a1fffffff", Numeric.toHexString(buf).substring(2));
    }

    @Test
    void encodeUint256ShouldPadTo32Bytes() {
        byte[] encoded = EpochMerkleBuilder.encodeUint256(BigInteger.ONE);
        assertEquals(32, encoded.length);
        // Last byte should be 1, all others 0
        assertEquals(1, Byte.toUnsignedInt(encoded[31]));
        for (int i = 0; i < 31; i++) assertEquals(0, Byte.toUnsignedInt(encoded[i]));
    }

    @Test
    void encodeUint256ShouldHandleLargeValue() {
        BigInteger large = new BigInteger("684931506849315068493");
        byte[] encoded = EpochMerkleBuilder.encodeUint256(large);
        assertEquals(32, encoded.length);
        // Round-trip through BigInteger
        assertEquals(large, new BigInteger(1, encoded));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DATA leaf
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dataLeafShouldBeDeterministicFor32Bytes() {
        ReadingSubmissionRequest r = reading("0x1111111111111111111111111111111111111111",
                "0x0882830a1fffffff", 100L, 100, 200);

        byte[] leaf1 = EpochMerkleBuilder.dataLeaf(r);
        byte[] leaf2 = EpochMerkleBuilder.dataLeaf(r);

        assertEquals(32, leaf1.length);
        assertArrayEquals(leaf1, leaf2);
    }

    @Test
    void dataLeafShouldDifferOnAqiChange() {
        ReadingSubmissionRequest r1 = reading("0x1111111111111111111111111111111111111111",
                "0x0882830a1fffffff", 100L, 100, 200);
        ReadingSubmissionRequest r2 = reading("0x1111111111111111111111111111111111111111",
                "0x0882830a1fffffff", 100L, 200, 200);

        assertFalse(java.util.Arrays.equals(
                EpochMerkleBuilder.dataLeaf(r1), EpochMerkleBuilder.dataLeaf(r2)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OZ-compatible tree construction
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void singleLeafRootShouldBeTheLeafItself() {
        byte[] leaf = new byte[32];
        leaf[31] = 0x42;
        byte[] root = EpochMerkleBuilder.buildRoot(List.of(leaf));
        assertArrayEquals(leaf, root);
    }

    @Test
    void hashPairShouldBeCommutative() {
        byte[] a = new byte[32];
        byte[] b = new byte[32];
        a[31] = 0x01;
        b[31] = 0x02;

        assertArrayEquals(EpochMerkleBuilder.hashPair(a, b), EpochMerkleBuilder.hashPair(b, a));
    }

    @Test
    void twoLeafRootShouldMatchManualHashPair() {
        byte[] leaf1 = EpochMerkleBuilder.dataLeaf(
                reading("0x1111111111111111111111111111111111111111", "0x0882830a1fffffff", 100L, 100, 200));
        byte[] leaf2 = EpochMerkleBuilder.dataLeaf(
                reading("0x2222222222222222222222222222222222222222", "0x0882830a1fffffff", 100L, 120, 200));

        byte[] root = EpochMerkleBuilder.buildRoot(List.of(leaf1, leaf2));
        byte[] expected = EpochMerkleBuilder.hashPair(leaf1, leaf2);

        assertArrayEquals(expected, root);
    }

    @Test
    void buildDataRootWithDifferentReadingOrderShouldGiveSameRoot() {
        // Sorting by sort key must make the root independent of submission order
        ReadingSubmissionRequest r1 = reading("0x1111111111111111111111111111111111111111",
                "0x0882830a1fffffff", 100L, 100, 200);
        ReadingSubmissionRequest r2 = reading("0x2222222222222222222222222222222222222222",
                "0x0882830a1fffffff", 100L, 120, 200);

        byte[] rootAB = EpochMerkleBuilder.buildDataRoot(List.of(r1, r2));
        byte[] rootBA = EpochMerkleBuilder.buildDataRoot(List.of(r2, r1));

        assertArrayEquals(rootAB, rootBA);
    }

    @Test
    void buildDataRootShouldDifferWhenReadingChanges() {
        ReadingSubmissionRequest r1 = reading("0x1111111111111111111111111111111111111111",
                "0x0882830a1fffffff", 100L, 100, 200);
        ReadingSubmissionRequest r2 = reading("0x2222222222222222222222222222222222222222",
                "0x0882830a1fffffff", 100L, 120, 200);
        ReadingSubmissionRequest r2Modified = reading("0x2222222222222222222222222222222222222222",
                "0x0882830a1fffffff", 100L, 121, 200); // aqi changed

        byte[] original = EpochMerkleBuilder.buildDataRoot(List.of(r1, r2));
        byte[] modified = EpochMerkleBuilder.buildDataRoot(List.of(r1, r2Modified));

        assertNotEquals(Numeric.toHexString(original), Numeric.toHexString(modified));
    }

    @Test
    void buildRewardRootShouldBeDeterministic() {
        List<ReporterReward> rewards = List.of(
                new ReporterReward("0x2222222222222222222222222222222222222222",
                        EpochMerkleBuilder.parseH3Index("0x0882830a1fffffff"), BigInteger.valueOf(500)),
                new ReporterReward("0x1111111111111111111111111111111111111111",
                        EpochMerkleBuilder.parseH3Index("0x0882830a1fffffff"), BigInteger.valueOf(300))
        );

        // reverse order should give same root (sorted by reporter address)
        List<ReporterReward> reversed = List.of(rewards.get(1), rewards.get(0));

        byte[] root1 = EpochMerkleBuilder.buildRewardRoot(rewards, 100L);
        byte[] root2 = EpochMerkleBuilder.buildRewardRoot(reversed, 100L);

        assertArrayEquals(root1, root2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────────

    private static ReadingSubmissionRequest reading(
            String reporter, String h3Index, long epochId, int aqi, int pm25) {
        return new ReadingSubmissionRequest(
                reporter, h3Index, epochId, epochId * 3600 + 1,
                aqi, pm25, null, null, null, null, null,
                "0x" + "1".repeat(130)
        );
    }
}
