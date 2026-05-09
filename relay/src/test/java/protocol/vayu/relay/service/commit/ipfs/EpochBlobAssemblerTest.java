package protocol.vayu.relay.service.commit.ipfs;

import protocol.vayu.relay.service.commit.aggregation.CellAggregate;
import protocol.vayu.relay.service.commit.aggregation.EpochAggregate;
import protocol.vayu.relay.service.commit.aggregation.EpochMerkleBuilder;
import protocol.vayu.relay.service.commit.aggregation.ReporterReward;
import protocol.vayu.relay.service.commit.aggregation.ReporterScore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpochBlobAssemblerTest {

    private EpochBlobAssembler assembler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        assembler = new EpochBlobAssembler();
        objectMapper = new ObjectMapper();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Top-level fields
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void assembleShouldIncludeAllTopLevelScalarFields() throws Exception {
        EpochAggregate agg = aggregate(100L, 45, 12, 3, new byte[32], new byte[32], List.of(), List.of(), List.of());

        Map<String, Object> blob = parse(assembler.assemble(agg));

        assertEquals(100, blob.get("epochId"));
        assertEquals(45, blob.get("totalReadings"));
        assertEquals(12, blob.get("uniqueReporters"));
        assertEquals(3, blob.get("activeCells"));
    }

    @Test
    void assembleShouldSerialiseDataRootAsHexString() throws Exception {
        byte[] root = new byte[32];
        root[31] = 0x01;
        EpochAggregate agg = aggregate(1L, 0, 0, 0, root, new byte[32], List.of(), List.of(), List.of());

        Map<String, Object> blob = parse(assembler.assemble(agg));

        String dataRoot = (String) blob.get("dataRoot");
        assertTrue(dataRoot.startsWith("0x"), "dataRoot must start with 0x");
        assertEquals(66, dataRoot.length(), "dataRoot must be 32 bytes = 66 hex chars with 0x prefix");
        assertTrue(dataRoot.endsWith("01"), "last byte must be 0x01");
    }

    @Test
    void assembleShouldSerialiseRewardRootAsHexString() throws Exception {
        byte[] root = new byte[32];
        root[0] = (byte) 0xff;
        EpochAggregate agg = aggregate(1L, 0, 0, 0, new byte[32], root, List.of(), List.of(), List.of());

        Map<String, Object> blob = parse(assembler.assemble(agg));

        String rewardRoot = (String) blob.get("rewardRoot");
        assertTrue(rewardRoot.startsWith("0xff"), "rewardRoot must start with 0xff");
    }

    @Test
    void assembleShouldOutputNullForNullDataRoot() throws Exception {
        EpochAggregate agg = aggregate(1L, 0, 0, 0, null, null, List.of(), List.of(), List.of());

        Map<String, Object> blob = parse(assembler.assemble(agg));

        assertNull(blob.get("dataRoot"));
        assertNull(blob.get("rewardRoot"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cells
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void assembleShouldSerialiseCellsWithAllFields() throws Exception {
        ReporterScore rs = new ReporterScore("0x1111111111111111111111111111111111111111", 0.8);
        CellAggregate cell = new CellAggregate("0x0882830a1fffffff", 5, true, 120,
                30, 10, 5, 2, 1, 0, List.of(rs));
        EpochAggregate agg = aggregate(1L, 5, 1, 1, new byte[32], new byte[32],
                List.of(cell), List.of(), List.of());

        Map<String, Object> blob = parse(assembler.assemble(agg));

        List<?> cells = (List<?>) blob.get("cells");
        assertEquals(1, cells.size());

        Map<?, ?> c = (Map<?, ?>) cells.get(0);
        assertEquals("0x0882830a1fffffff", c.get("h3Index"));
        assertEquals(5, c.get("readingCount"));
        assertEquals(true, c.get("active"));
        assertEquals(120, c.get("medianAqi"));
        assertEquals(30, c.get("avgPm25"));

        List<?> scores = (List<?>) c.get("reporterScores");
        assertEquals(1, scores.size());
        Map<?, ?> score = (Map<?, ?>) scores.get(0);
        assertEquals("0x1111111111111111111111111111111111111111", score.get("reporter"));
        assertEquals(0.8, (Double) score.get("score"), 1e-9);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rewards
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void assembleShouldSerialiseRewardAmountAsDecimalString() throws Exception {
        BigInteger largeAmount = new BigInteger("684931506849315068493");
        long h3 = EpochMerkleBuilder.parseH3Index("0x0882830a1fffffff");
        ReporterReward reward = new ReporterReward("0x2222222222222222222222222222222222222222",
                h3, largeAmount);
        EpochAggregate agg = aggregate(1L, 0, 0, 0, new byte[32], new byte[32],
                List.of(), List.of(reward), List.of());

        Map<String, Object> blob = parse(assembler.assemble(agg));

        List<?> rewards = (List<?>) blob.get("rewards");
        assertEquals(1, rewards.size());
        Map<?, ?> r = (Map<?, ?>) rewards.get(0);

        // Amount must be a string to preserve precision
        assertEquals(largeAmount.toString(), r.get("amount"),
                "BigInteger amount must be serialised as a decimal string");
    }

    @Test
    void assembleShouldSerialiseRewardReporterAndH3() throws Exception {
        long h3 = EpochMerkleBuilder.parseH3Index("0x0882830a1fffffff");
        ReporterReward reward = new ReporterReward("0x3333333333333333333333333333333333333333",
                h3, BigInteger.valueOf(999));
        EpochAggregate agg = aggregate(1L, 0, 0, 0, new byte[32], new byte[32],
                List.of(), List.of(reward), List.of());

        Map<String, Object> blob = parse(assembler.assemble(agg));

        List<?> rewards = (List<?>) blob.get("rewards");
        Map<?, ?> r = (Map<?, ?>) rewards.get(0);

        assertEquals("0x3333333333333333333333333333333333333333", r.get("reporter"));
        assertEquals(h3, ((Number) r.get("h3IndexLong")).longValue());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Penalty list
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void assembleShouldIncludePenaltyList() throws Exception {
        String offender = "0x4444444444444444444444444444444444444444";
        EpochAggregate agg = aggregate(1L, 0, 0, 0, new byte[32], new byte[32],
                List.of(), List.of(), List.of(offender));

        Map<String, Object> blob = parse(assembler.assemble(agg));

        List<?> penaltyList = (List<?>) blob.get("penaltyList");
        assertEquals(1, penaltyList.size());
        assertEquals(offender, penaltyList.get(0));
    }

    @Test
    void assembleShouldIncludeEmptyPenaltyListWhenNoPenalties() throws Exception {
        EpochAggregate agg = aggregate(1L, 0, 0, 0, new byte[32], new byte[32],
                List.of(), List.of(), List.of());

        Map<String, Object> blob = parse(assembler.assemble(agg));

        List<?> penaltyList = (List<?>) blob.get("penaltyList");
        assertTrue(penaltyList.isEmpty());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Output is valid JSON
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void assembleShouldProduceValidJson() throws Exception {
        EpochAggregate agg = aggregate(99L, 10, 3, 1, new byte[32], new byte[32],
                List.of(), List.of(), List.of());

        String json = assembler.assemble(agg);

        // Should parse without exception
        objectMapper.readTree(json);
    }

    @Test
    void assembleShouldBeDeterministic() {
        EpochAggregate agg = aggregate(50L, 20, 5, 2, new byte[32], new byte[32],
                List.of(), List.of(), List.of());

        String first = assembler.assemble(agg);
        String second = assembler.assemble(agg);

        assertEquals(first, second, "Same aggregate must always produce the same JSON");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static EpochAggregate aggregate(long epochId, int totalReadings, int uniqueReporters,
            int activeCells, byte[] dataRoot, byte[] rewardRoot,
            List<CellAggregate> cells, List<ReporterReward> rewards, List<String> penaltyList) {
        return new EpochAggregate(epochId, totalReadings, uniqueReporters,
                cells, activeCells, rewards, dataRoot, rewardRoot, penaltyList);
    }

    private Map<String, Object> parse(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {});
    }
}
