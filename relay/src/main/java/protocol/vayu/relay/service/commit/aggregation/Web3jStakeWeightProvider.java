package protocol.vayu.relay.service.commit.aggregation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

/**
 * StakeWeightProvider that reads a reporter's active protocol stake via
 * an {@code eth_call} to {@code VayuEpochSettlement.reporterStake(address)}.
 *
 * <p>Only tokens explicitly locked in the settlement contract (slashable, active)
 * contribute to weight — wallet balance alone carries no protocol commitment.
 * Active when {@code relay.security.stake-check-enabled=true}.
 */
public class Web3jStakeWeightProvider implements StakeWeightProvider {

    private static final Logger LOG = LoggerFactory.getLogger(Web3jStakeWeightProvider.class);

    private final Web3j web3j;
    private final String settlementAddress;

    public Web3jStakeWeightProvider(Web3j web3j, String settlementAddress) {
        this.web3j = web3j;
        this.settlementAddress = settlementAddress;
    }

    @Override
    public BigInteger stakeOf(String reporter) {
        Function function = new Function(
                "reporterStake",
                List.of(new Address(reporter)),
                List.of(new TypeReference<Uint256>() {})
        );

        String encoded = FunctionEncoder.encode(function);
        Transaction call = Transaction.createEthCallTransaction(null, settlementAddress, encoded);

        EthCall response;
        try {
            response = web3j.ethCall(call, DefaultBlockParameterName.LATEST).send();
        } catch (IOException e) {
            throw new StakeQueryException("eth_call failed for reporter " + reporter, e);
        }

        if (response.hasError()) {
            throw new StakeQueryException("eth_call reverted for reporter " + reporter
                    + ": " + response.getError().getMessage());
        }

        String value = response.getValue();
        if (value == null || value.equals("0x")) {
            LOG.debug("empty reporterStake result for {}, treating as zero stake", reporter);
            return BigInteger.ZERO;
        }

        @SuppressWarnings("rawtypes")
        List<Type> decoded = FunctionReturnDecoder.decode(value, function.getOutputParameters());
        if (decoded.isEmpty()) {
            LOG.debug("could not decode reporterStake result for {}, treating as zero stake", reporter);
            return BigInteger.ZERO;
        }

        BigInteger stake = (BigInteger) decoded.get(0).getValue();
        LOG.debug("reporter {} active stake: {} wei", reporter, stake);
        return stake;
    }
}
