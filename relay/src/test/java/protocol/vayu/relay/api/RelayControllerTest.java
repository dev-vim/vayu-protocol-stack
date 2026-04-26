package protocol.vayu.relay.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Objects;

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
        String payload = """
                {
                  "reporter": "0x1111111111111111111111111111111111111111",
                  "h3Index": "0x0882830a1fffffff",
                  "aqi": 148,
                  "pm25": 453,
                  "pm10": 821,
                  "o3": 0,
                  "no2": 0,
                  "so2": 0,
                  "co": 0,
                  "timestamp": 1743954800,
                  "signature": "0x111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111b"
                }
                """;

        mockMvc.perform(post("/v1/readings")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(payload))
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
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }
}
