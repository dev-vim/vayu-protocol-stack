package protocol.vayu.relay.service.commit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.utils.Numeric;
import protocol.vayu.relay.service.commit.aggregation.EpochAggregate;
import protocol.vayu.relay.service.commit.ipfs.EpochBlobAssembler;
import protocol.vayu.relay.service.commit.ipfs.IpfsPinClient;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Production publisher: ABI-encodes {@code commitEpoch} and submits a signed
 * EIP-155 transaction to {@code VayuEpochSettlement} via web3j.
 *
 * <p>Active when {@code relay.chain.on-chain-commit-enabled=true}.
 *
 * <p>The relay wallet must be pre-registered on the settlement contract
 * (see {@code VayuEpochSettlement.registerRelay()}). The startup guard in
 * {@code ChainConfig} enforces this before the application accepts traffic.
 */
public class Web3jEpochCommitPublisher implements EpochCommitPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(Web3jEpochCommitPublisher.class);

    /** 20 % buffer applied on top of the {@code eth_estimateGas} result. */
    private static final BigInteger GAS_BUFFER_NUMERATOR   = BigInteger.valueOf(120);
    private static final BigInteger GAS_BUFFER_DENOMINATOR = BigInteger.valueOf(100);

    private final Web3j web3j;
    private final Credentials credentials;
    private final String settlementAddress;
    private final long chainId;
    private final EpochBlobAssembler blobAssembler;
    private final IpfsPinClient ipfsPinClient;

    public Web3jEpochCommitPublisher(
            Web3j web3j,
            Credentials credentials,
            String settlementAddress,
            long chainId,
            EpochBlobAssembler blobAssembler,
            IpfsPinClient ipfsPinClient) {
        this.web3j             = web3j;
        this.credentials       = credentials;
        this.settlementAddress = settlementAddress;
        this.chainId           = chainId;
        this.blobAssembler     = blobAssembler;
        this.ipfsPinClient     = ipfsPinClient;
    }

    @Override
    public CommitPublication publish(EpochAggregate aggregate) {
        if (aggregate.dataRoot() == null || aggregate.rewardRoot() == null) {
            throw new EpochCommitException(
                    "Epoch " + aggregate.epochId() + " is missing dataRoot or rewardRoot; " +
                    "ensure ProtocolEpochAggregator is active when on-chain commit is enabled.");
        }

        long submittedAt = Instant.now().getEpochSecond();

        String jsonBlob = blobAssembler.assemble(aggregate);
        String ipfsCid  = ipfsPinClient.pin(aggregate.epochId(), jsonBlob);
        LOG.info("epoch {} blob pinned: cid={}", aggregate.epochId(), ipfsCid);

        String data = encodeCommitEpoch(aggregate, ipfsCid);

        BigInteger gasPrice = fetchGasPrice();
        BigInteger gasLimit = estimateGas(data);
        BigInteger nonce    = fetchNonce();

        RawTransaction rawTx = RawTransaction.createTransaction(
                nonce, gasPrice, gasLimit, settlementAddress, BigInteger.ZERO, data);
        byte[] signed = TransactionEncoder.signMessage(rawTx, chainId, credentials);

        EthSendTransaction sent;
        try {
            sent = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send();
        } catch (IOException e) {
            throw new EpochCommitException(
                    "Failed to submit commitEpoch for epoch " + aggregate.epochId(), e);
        }

        if (sent.hasError()) {
            throw new EpochCommitException(
                    "commitEpoch rejected for epoch " + aggregate.epochId() +
                    ": " + sent.getError().getMessage());
        }

        String txHash = sent.getTransactionHash();
        LOG.info("commitEpoch submitted: epochId={}, txHash={}, ipfsCid={}",
                aggregate.epochId(), txHash, ipfsCid);

        return new CommitPublication(
                aggregate.epochId(), txHash, ipfsCid, aggregate.totalReadings(), submittedAt);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String encodeCommitEpoch(EpochAggregate aggregate, String ipfsCid) {
        List<Address> penaltyAddresses = aggregate.penaltyList().stream()
                .map(Address::new)
                .collect(Collectors.toList());

        Function function = new Function(
                "commitEpoch",
                List.of(
                        new Uint32(BigInteger.valueOf(aggregate.epochId())),
                        new Bytes32(aggregate.dataRoot()),
                        new Bytes32(aggregate.rewardRoot()),
                        new Utf8String(ipfsCid),
                        new Uint32(BigInteger.valueOf(aggregate.activeCells())),
                        new Uint32(BigInteger.valueOf(aggregate.totalReadings())),
                        new DynamicArray<>(Address.class, penaltyAddresses)
                ),
                Collections.emptyList()
        );
        return FunctionEncoder.encode(function);
    }

    private BigInteger fetchGasPrice() {
        EthGasPrice response;
        try {
            response = web3j.ethGasPrice().send();
        } catch (IOException e) {
            throw new EpochCommitException("eth_gasPrice request failed", e);
        }
        if (response.hasError()) {
            throw new EpochCommitException("eth_gasPrice failed: " + response.getError().getMessage());
        }
        return response.getGasPrice();
    }

    private BigInteger estimateGas(String data) {
        Transaction tx = Transaction.createEthCallTransaction(
                credentials.getAddress(), settlementAddress, data);
        EthEstimateGas response;
        try {
            response = web3j.ethEstimateGas(tx).send();
        } catch (IOException e) {
            throw new EpochCommitException("eth_estimateGas request failed", e);
        }
        if (response.hasError()) {
            throw new EpochCommitException("eth_estimateGas failed: " + response.getError().getMessage());
        }
        return response.getAmountUsed()
                       .multiply(GAS_BUFFER_NUMERATOR)
                       .divide(GAS_BUFFER_DENOMINATOR);
    }

    private BigInteger fetchNonce() {
        EthGetTransactionCount response;
        try {
            response = web3j.ethGetTransactionCount(
                    credentials.getAddress(), DefaultBlockParameterName.PENDING).send();
        } catch (IOException e) {
            throw new EpochCommitException("eth_getTransactionCount request failed", e);
        }
        if (response.hasError()) {
            throw new EpochCommitException(
                    "eth_getTransactionCount failed: " + response.getError().getMessage());
        }
        return response.getTransactionCount();
    }
}
