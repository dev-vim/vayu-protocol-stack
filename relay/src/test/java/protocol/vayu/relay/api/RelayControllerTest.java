package protocol.vayu.relay.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Objects;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RelayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthShouldReturn200() throws Exception {
        mockMvc.perform(get("/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("healthy"));
    }

    @Test
    void submitReadingShouldAcceptValidPayload() throws Exception {
        long now = Instant.now().getEpochSecond();
        String payload = payload(
                "0x1111111111111111111111111111111111111111",
                "0x0882830a1fffffff",
                148,
                453,
                now,
                true
        );

        mockMvc.perform(post("/v1/readings")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(Objects.requireNonNull(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    void submitReadingShouldRejectInvalidPayload() throws Exception {
        String payload = """
                {
                  "reporter": "0xINVALID",
                  "h3Index": "0x1",
                  "aqi": 0,
                  "pm25": 0,
                  "timestamp": 0,
                  "signature": "0x1234"
                }
                """;

        mockMvc.perform(post("/v1/readings")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(Objects.requireNonNull(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void submitReadingShouldRejectStaleTimestamp() throws Exception {
        long staleTimestamp = Instant.now().minusSeconds(1200).getEpochSecond();
        String payload = payload(
                "0x2222222222222222222222222222222222222222",
                "0x0882830a1fffffff",
                148,
                453,
                staleTimestamp,
                false
        );

        mockMvc.perform(post("/v1/readings")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(Objects.requireNonNull(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void submitReadingShouldRejectWrongH3Resolution() throws Exception {
        long now = Instant.now().getEpochSecond();
        String payload = payload(
                "0x3333333333333333333333333333333333333333",
                "0x0872830a1fffffff",
                148,
                453,
                now,
                false
        );

        mockMvc.perform(post("/v1/readings")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                                                .content(Objects.requireNonNull(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void submitReadingShouldRateLimitSameReporter() throws Exception {
        long now = Instant.now().getEpochSecond();
        String payload = payload(
                "0x4444444444444444444444444444444444444444",
                "0x0882830a1fffffff",
                180,
                500,
                now,
                false
        );

        mockMvc.perform(post("/v1/readings")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(Objects.requireNonNull(payload)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/readings")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(Objects.requireNonNull(payload)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("rate_limited"))
                .andExpect(jsonPath("$.retryAfter").value(Objects.requireNonNull(greaterThan(0))));
    }

    @Test
    void submitReadingShouldRejectFutureTimestampOutsideTolerance() throws Exception {
        long futureTimestamp = Instant.now().plusSeconds(1200).getEpochSecond();
        String payload = payload(
                "0x5555555555555555555555555555555555555555",
                "0x0882830a1fffffff",
                170,
                490,
                futureTimestamp,
                false
        );

        mockMvc.perform(post("/v1/readings")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(Objects.requireNonNull(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void submitReadingShouldAcceptRequiredFieldsOnly() throws Exception {
        long now = Instant.now().getEpochSecond();
        String payload = payload(
                "0x6666666666666666666666666666666666666666",
                "0x0882830a1fffffff",
                101,
                251,
                now,
                false
        );

        mockMvc.perform(post("/v1/readings")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(Objects.requireNonNull(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    private static String payload(
            String reporter,
            String h3Index,
            int aqi,
            int pm25,
            long timestamp,
            boolean includeOptionalPollutants
    ) {
        String optional = includeOptionalPollutants
                ? "\n  \"pm10\": 821,\n  \"o3\": 0,\n  \"no2\": 0,\n  \"so2\": 0,\n  \"co\": 0,"
                : "";

        return """
                {
                  "reporter": "%s",
                  "h3Index": "%s",
                  "aqi": %d,
                  "pm25": %d,%s
                  "timestamp": %d,
                  "signature": "0x111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111b"
                }
                """.formatted(reporter, h3Index, aqi, pm25, optional, timestamp);
    }
}
