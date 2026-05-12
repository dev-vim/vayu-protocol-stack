package protocol.vayu.relay.service.commit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import protocol.vayu.relay.service.commit.aggregation.EpochAggregate;
import protocol.vayu.relay.service.commit.ipfs.EpochBlobAssembler;
import protocol.vayu.relay.service.commit.ipfs.IpfsPinClient;
import protocol.vayu.relay.service.commit.ipfs.IpfsPinException;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Web3jEpochCommitPublisherTest {

    // A well-known test private key — no real funds, used throughout web3j docs.
    private static final String TEST_PRIVATE_KEY =
            "a392604efc2fad9c0b3da43b5f698a2e3f270f170d859912be0d54742275c5f6";
    private static final String SETTLEMENT = "0xDeadBeefDeadBeefDeadBeefDeadBeefDeadBeef";
    private static final long   CHAIN_ID   = 84532L;
    private static final String TX_HASH    = "0x" + "ab".repeat(32);
    private static final String IPFS_CID   = "QmTestCidAbcdef1234567890";

    @Mock private Web3j              web3j;
    @Mock private EpochBlobAssembler blobAssembler;
    @Mock private IpfsPinClient      ipfsPinClient;

    private Web3jEpochCommitPublisher publisher;
    private EpochAggregate            aggregate;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Credentials credentials = Credentials.create(TEST_PRIVATE_KEY);
        publisher = new Web3jEpochCommitPublisher(
                web3j, credentials, SETTLEMENT, CHAIN_ID, blobAssembler, ipfsPinClient);

        byte[] dataRoot   = new byte[32]; Arrays.fill(dataRoot,   (byte) 1);
        byte[] rewardRoot = new byte[32]; Arrays.fill(rewardRoot, (byte) 2);
        aggregate = new EpochAggregate(1L, 5, 3, List.of(), 2, List.of(), dataRoot, rewardRoot, List.of(), List.of());

        when(blobAssembler.assemble(any())).thenReturn("{\"epochId\":1}");
        when(ipfsPinClient.pin(any(Long.class), any())).thenReturn(IPFS_CID);
    }

    // ---- helpers --------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubGasPrice(String hexValue) throws IOException {
        EthGasPrice resp = new EthGasPrice();
        resp.setResult(hexValue);
        Request<?, EthGasPrice> req = mock(Request.class);
        when(req.send()).thenReturn(resp);
        doReturn(req).when(web3j).ethGasPrice();
    }

    @SuppressWarnings("unchecked")
    private void stubGasPriceThrows(IOException ex) throws IOException {
        Request<?, EthGasPrice> req = mock(Request.class);
        when(req.send()).thenThrow(ex);
        doReturn(req).when(web3j).ethGasPrice();
    }

    @SuppressWarnings("unchecked")
    private void stubEstimateGas(String hexValue) throws IOException {
        EthEstimateGas resp = new EthEstimateGas();
        resp.setResult(hexValue);
        Request<?, EthEstimateGas> req = mock(Request.class);
        when(req.send()).thenReturn(resp);
        doReturn(req).when(web3j).ethEstimateGas(any());
    }

    @SuppressWarnings("unchecked")
    private void stubEstimateGasThrows(IOException ex) throws IOException {
        Request<?, EthEstimateGas> req = mock(Request.class);
        when(req.send()).thenThrow(ex);
        doReturn(req).when(web3j).ethEstimateGas(any());
    }

    @SuppressWarnings("unchecked")
    private void stubNonce(String hexValue) throws IOException {
        EthGetTransactionCount resp = new EthGetTransactionCount();
        resp.setResult(hexValue);
        Request<?, EthGetTransactionCount> req = mock(Request.class);
        when(req.send()).thenReturn(resp);
        doReturn(req).when(web3j).ethGetTransactionCount(any(), any());
    }

    @SuppressWarnings("unchecked")
    private void stubSendRawTx(String txHash) throws IOException {
        EthSendTransaction resp = new EthSendTransaction();
        resp.setResult(txHash);
        Request<?, EthSendTransaction> req = mock(Request.class);
        when(req.send()).thenReturn(resp);
        doReturn(req).when(web3j).ethSendRawTransaction(any());
    }

    @SuppressWarnings("unchecked")
    private void stubSendRawTxThrows(IOException ex) throws IOException {
        Request<?, EthSendTransaction> req = mock(Request.class);
        when(req.send()).thenThrow(ex);
        doReturn(req).when(web3j).ethSendRawTransaction(any());
    }

    @SuppressWarnings("unchecked")
    private void stubSendRawTxError(String message) throws IOException {
        EthSendTransaction resp = new EthSendTransaction();
        EthSendTransaction.Error error = new EthSendTransaction.Error();
        error.setCode(-32000);
        error.setMessage(message);
        resp.setError(error);
        Request<?, EthSendTransaction> req = mock(Request.class);
        when(req.send()).thenReturn(resp);
        doReturn(req).when(web3j).ethSendRawTransaction(any());
    }

    @SuppressWarnings("unchecked")
    private void stubGasPriceError(String message) throws IOException {
        EthGasPrice resp = new EthGasPrice();
        EthGasPrice.Error error = new EthGasPrice.Error();
        error.setCode(-32000);
        error.setMessage(message);
        resp.setError(error);
        Request<?, EthGasPrice> req = mock(Request.class);
        when(req.send()).thenReturn(resp);
        doReturn(req).when(web3j).ethGasPrice();
    }

    @SuppressWarnings("unchecked")
    private void stubEstimateGasError(String message) throws IOException {
        EthEstimateGas resp = new EthEstimateGas();
        EthEstimateGas.Error error = new EthEstimateGas.Error();
        error.setCode(-32000);
        error.setMessage(message);
        resp.setError(error);
        Request<?, EthEstimateGas> req = mock(Request.class);
        when(req.send()).thenReturn(resp);
        doReturn(req).when(web3j).ethEstimateGas(any());
    }

    @SuppressWarnings("unchecked")
    private void stubNonceThrows(IOException ex) throws IOException {
        Request<?, EthGetTransactionCount> req = mock(Request.class);
        when(req.send()).thenThrow(ex);
        doReturn(req).when(web3j).ethGetTransactionCount(any(), any());
    }

    @SuppressWarnings("unchecked")
    private void stubNonceError(String message) throws IOException {
        EthGetTransactionCount resp = new EthGetTransactionCount();
        EthGetTransactionCount.Error error = new EthGetTransactionCount.Error();
        error.setCode(-32000);
        error.setMessage(message);
        resp.setError(error);
        Request<?, EthGetTransactionCount> req = mock(Request.class);
        when(req.send()).thenReturn(resp);
        doReturn(req).when(web3j).ethGetTransactionCount(any(), any());
    }

    // ---- tests ----------------------------------------------------------

    @Test
    void publishShouldReturnCommitPublicationWithChainData() throws IOException {
        stubGasPrice("0x3B9ACA00");   // 1 gwei
        stubEstimateGas("0x186A0");   // 100_000
        stubNonce("0x0");
        stubSendRawTx(TX_HASH);

        CommitPublication result = publisher.publish(aggregate);

        assertThat(result.epochId()).isEqualTo(1L);
        assertThat(result.txHash()).isEqualTo(TX_HASH);
        assertThat(result.ipfsCid()).isEqualTo(IPFS_CID);
        assertThat(result.readingCount()).isEqualTo(5);
        assertThat(result.submittedAt()).isPositive();
    }

    @Test
    void publishShouldThrowWhenDataRootIsNull() {
        EpochAggregate noRoot = new EpochAggregate(
                2L, 3, 2, List.of(), 1, List.of(), null, new byte[32], List.of(), List.of());

        assertThatThrownBy(() -> publisher.publish(noRoot))
                .isInstanceOf(EpochCommitException.class)
                .hasMessageContaining("dataRoot")
                .hasMessageContaining("2");
    }

    @Test
    void publishShouldThrowWhenRewardRootIsNull() {
        EpochAggregate noRoot = new EpochAggregate(
                3L, 3, 2, List.of(), 1, List.of(), new byte[32], null, List.of(), List.of());

        assertThatThrownBy(() -> publisher.publish(noRoot))
                .isInstanceOf(EpochCommitException.class)
                .hasMessageContaining("rewardRoot")
                .hasMessageContaining("3");
    }

    @Test
    void publishShouldThrowOnGasPriceIoFailure() throws IOException {
        stubGasPriceThrows(new IOException("connection refused"));

        assertThatThrownBy(() -> publisher.publish(aggregate))
                .isInstanceOf(EpochCommitException.class)
                .hasMessageContaining("eth_gasPrice")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void publishShouldThrowOnEstimateGasIoFailure() throws IOException {
        stubGasPrice("0x3B9ACA00");
        stubEstimateGasThrows(new IOException("rpc timeout"));

        assertThatThrownBy(() -> publisher.publish(aggregate))
                .isInstanceOf(EpochCommitException.class)
                .hasMessageContaining("eth_estimateGas")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void publishShouldThrowOnSendTransactionIoFailure() throws IOException {
        stubGasPrice("0x3B9ACA00");
        stubEstimateGas("0x186A0");
        stubNonce("0x0");
        stubSendRawTxThrows(new IOException("node unreachable"));

        assertThatThrownBy(() -> publisher.publish(aggregate))
                .isInstanceOf(EpochCommitException.class)
                .hasMessageContaining("commitEpoch")
                .hasMessageContaining("1")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void publishShouldThrowWhenTransactionIsRejected() throws IOException {
        stubGasPrice("0x3B9ACA00");
        stubEstimateGas("0x186A0");
        stubNonce("0x0");
        stubSendRawTxError("NotActiveRelay");

        assertThatThrownBy(() -> publisher.publish(aggregate))
                .isInstanceOf(EpochCommitException.class)
                .hasMessageContaining("rejected")
                .hasMessageContaining("1")
                .hasMessageContaining("NotActiveRelay");
    }

    @Test
    void publishShouldPropagateIpfsPinException() {
        when(ipfsPinClient.pin(any(Long.class), any()))
                .thenThrow(new IpfsPinException("kubo unavailable"));

        assertThatThrownBy(() -> publisher.publish(aggregate))
                .isInstanceOf(IpfsPinException.class)
                .hasMessageContaining("kubo unavailable");
    }

    @Test
    void publishShouldThrowOnGasPriceRpcError() throws IOException {
        stubGasPriceError("method not found");

        assertThatThrownBy(() -> publisher.publish(aggregate))
                .isInstanceOf(EpochCommitException.class)
                .hasMessageContaining("eth_gasPrice")
                .hasMessageContaining("method not found");
    }

    @Test
    void publishShouldThrowOnEstimateGasRpcError() throws IOException {
        stubGasPrice("0x3B9ACA00");
        stubEstimateGasError("execution reverted");

        assertThatThrownBy(() -> publisher.publish(aggregate))
                .isInstanceOf(EpochCommitException.class)
                .hasMessageContaining("eth_estimateGas")
                .hasMessageContaining("execution reverted");
    }

    @Test
    void publishShouldThrowOnNonceIoFailure() throws IOException {
        stubGasPrice("0x3B9ACA00");
        stubEstimateGas("0x186A0");
        stubNonceThrows(new IOException("connection reset"));

        assertThatThrownBy(() -> publisher.publish(aggregate))
                .isInstanceOf(EpochCommitException.class)
                .hasMessageContaining("eth_getTransactionCount")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void publishShouldThrowOnNonceRpcError() throws IOException {
        stubGasPrice("0x3B9ACA00");
        stubEstimateGas("0x186A0");
        stubNonceError("nonce too low");

        assertThatThrownBy(() -> publisher.publish(aggregate))
                .isInstanceOf(EpochCommitException.class)
                .hasMessageContaining("eth_getTransactionCount")
                .hasMessageContaining("nonce too low");
    }

    @Test
    void publishShouldSucceedWithNonEmptyPenaltyList() throws IOException {
        stubGasPrice("0x3B9ACA00");
        stubEstimateGas("0x186A0");
        stubNonce("0x1");
        stubSendRawTx(TX_HASH);

        byte[] dataRoot   = new byte[32]; Arrays.fill(dataRoot,   (byte) 1);
        byte[] rewardRoot = new byte[32]; Arrays.fill(rewardRoot, (byte) 2);
        EpochAggregate withPenalties = new EpochAggregate(
                2L, 3, 2, List.of(), 1, List.of(), dataRoot, rewardRoot,
                List.of(),
                List.of("0xDeadBeefDeadBeefDeadBeefDeadBeefDeadBeef"));

        CommitPublication result = publisher.publish(withPenalties);

        assertThat(result.epochId()).isEqualTo(2L);
        assertThat(result.txHash()).isEqualTo(TX_HASH);
    }
}
