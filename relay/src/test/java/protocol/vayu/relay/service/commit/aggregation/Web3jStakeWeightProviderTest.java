package protocol.vayu.relay.service.commit.aggregation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthCall;

import java.io.IOException;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Web3jStakeWeightProviderTest {

    private static final String SETTLEMENT = "0xDeadBeefDeadBeefDeadBeefDeadBeefDeadBeef";
    private static final String REPORTER = "0x1111111111111111111111111111111111111111";

    @Mock
    private Web3j web3j;

    private Web3jStakeWeightProvider provider;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        provider = new Web3jStakeWeightProvider(web3j, SETTLEMENT);
    }

    // ---- helpers --------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubEthCall(EthCall response) throws IOException {
        Request<?, EthCall> req = mock(Request.class);
        when(req.send()).thenReturn(response);
        doReturn(req).when(web3j).ethCall(any(), any());
    }

    @SuppressWarnings("unchecked")
    private void stubEthCallThrows(IOException ex) throws IOException {
        Request<?, EthCall> req = mock(Request.class);
        when(req.send()).thenThrow(ex);
        doReturn(req).when(web3j).ethCall(any(), any());
    }

    private EthCall successResponse(BigInteger value) {
        // ABI-encode a single uint256 as 32-byte hex
        String hex = "0x" + String.format("%064x", value);
        EthCall call = new EthCall();
        call.setResult(hex);
        return call;
    }

    private EthCall errorResponse(String message) {
        EthCall call = new EthCall();
        EthCall.Error error = new EthCall.Error();
        error.setCode(-32000);
        error.setMessage(message);
        call.setError(error);
        return call;
    }

    // ---- tests ----------------------------------------------------------

    @Test
    void stakeOfShouldReturnBalanceFromChain() throws IOException {
        BigInteger expected = new BigInteger("1000000000000000000"); // 1 VAYU
        stubEthCall(successResponse(expected));

        assertThat(provider.stakeOf(REPORTER)).isEqualTo(expected);
    }

    @Test
    void stakeOfShouldReturnZeroWhenResponseIsEmpty() throws IOException {
        EthCall call = new EthCall();
        call.setResult("0x");
        stubEthCall(call);

        assertThat(provider.stakeOf(REPORTER)).isEqualTo(BigInteger.ZERO);
    }

    @Test
    void stakeOfShouldReturnZeroForZeroBalance() throws IOException {
        stubEthCall(successResponse(BigInteger.ZERO));

        assertThat(provider.stakeOf(REPORTER)).isEqualTo(BigInteger.ZERO);
    }

    @Test
    void stakeOfShouldReturnLargeBalance() throws IOException {
        BigInteger large = new BigInteger("60000000000000000000000000"); // 60M VAYU
        stubEthCall(successResponse(large));

        assertThat(provider.stakeOf(REPORTER)).isEqualTo(large);
    }

    @Test
    void stakeOfShouldThrowStakeQueryExceptionOnIoFailure() throws IOException {
        stubEthCallThrows(new IOException("connection refused"));

        assertThatThrownBy(() -> provider.stakeOf(REPORTER))
                .isInstanceOf(StakeQueryException.class)
                .hasMessageContaining("eth_call failed")
                .hasMessageContaining(REPORTER)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void stakeOfShouldThrowStakeQueryExceptionOnRevertError() throws IOException {
        stubEthCall(errorResponse("execution reverted"));

        assertThatThrownBy(() -> provider.stakeOf(REPORTER))
                .isInstanceOf(StakeQueryException.class)
                .hasMessageContaining("eth_call reverted")
                .hasMessageContaining(REPORTER)
                .hasMessageContaining("execution reverted");
    }

    @Test
    void stakeOfShouldWorkForChecksummedAddress() throws IOException {
        BigInteger expected = BigInteger.TEN;
        // Checksummed address (mixed case) — web3j Address normalises it
        String checksummed = "0xAbCdEf0123456789AbCdEf0123456789AbCdEf01";
        stubEthCall(successResponse(expected));

        assertThat(provider.stakeOf(checksummed)).isEqualTo(expected);
    }
}
