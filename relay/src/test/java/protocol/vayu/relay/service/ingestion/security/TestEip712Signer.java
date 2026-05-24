package protocol.vayu.relay.service.ingestion.security;

import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;
import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;

import java.math.BigInteger;

/**
 * Test-only helper that produces a valid EIP-712 signature for a given request.
 * Lives in the same package as {@link Eip712SignatureVerifier} to access its
 * package-private {@code buildDigest} method.
 */
public class TestEip712Signer {

    private final Eip712SignatureVerifier verifier;

    public TestEip712Signer(Eip712SignatureVerifier verifier) {
        this.verifier = verifier;
    }

    /**
     * Signs {@code request} with the given private key and returns a 0x-prefixed
     * 65-byte hex signature (r || s || v).
     */
    public String sign(ReadingSubmissionRequest request, String privateKeyHex) {
        String stripped = privateKeyHex.startsWith("0x") ? privateKeyHex.substring(2) : privateKeyHex;
        ECKeyPair keyPair = ECKeyPair.create(new BigInteger(stripped, 16));

        byte[] digest = verifier.buildDigest(request);
        Sign.SignatureData sig = Sign.signMessage(digest, keyPair, false);

        byte[] full = new byte[65];
        System.arraycopy(sig.getR(), 0, full, 0, 32);
        System.arraycopy(sig.getS(), 0, full, 32, 32);
        full[64] = sig.getV()[0];
        return Numeric.toHexString(full);
    }
}
