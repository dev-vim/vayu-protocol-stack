package protocol.vayu.relay.service.ingestion.security;

import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import protocol.vayu.relay.config.RelayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.crypto.Sign.SignatureData;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Component
@ConditionalOnProperty(prefix = "relay.security", name = "signature-verification-enabled", havingValue = "true")
public class Eip712SignatureVerifier implements SignatureVerifier {

    private static final byte[] DOMAIN_TYPEHASH = Hash.sha3(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
                    .getBytes(StandardCharsets.UTF_8)
    );

    private static final byte[] READING_TYPEHASH = Hash.sha3(
            "AQIReading(address reporter,uint64 h3Index,uint32 epochId,uint32 timestamp,uint16 aqi,uint16 pm25,uint16 pm10,uint16 o3,uint16 no2,uint16 so2,uint16 co)"
                    .getBytes(StandardCharsets.UTF_8)
    );

    private final RelayProperties relayProperties;

    public Eip712SignatureVerifier(RelayProperties relayProperties) {
        this.relayProperties = relayProperties;
    }

    @Override
    public boolean verify(ReadingSubmissionRequest request) {
        try {
            byte[] digest = buildDigest(request);
            SignatureData signature = parseSignature(request.signature());
            int recId = toRecoveryId(signature.getV()[0]);
            if (recId < 0) {
                return false;
            }

            ECDSASignature ecdsa = new ECDSASignature(
                    new BigInteger(1, signature.getR()),
                    new BigInteger(1, signature.getS())
            );
            BigInteger recoveredPubKey = Sign.recoverFromSignature(recId, ecdsa, digest);
            if (recoveredPubKey == null) {
                return false;
            }

            String recoveredAddress = "0x" + org.web3j.crypto.Keys.getAddress(recoveredPubKey);
            return normalizeAddress(recoveredAddress).equals(normalizeAddress(request.reporter()));
        } catch (Exception ex) {
            return false;
        }
    }

    byte[] buildDigest(ReadingSubmissionRequest request) {
        byte[] domainSeparator = domainSeparator();
        byte[] structHash = readingStructHash(request);
        byte[] prefix = new byte[] {0x19, 0x01};
        return Hash.sha3(concat(prefix, domainSeparator, structHash));
    }

    private byte[] domainSeparator() {
        RelayProperties.Eip712 eip712 = relayProperties.security().eip712();
        byte[] nameHash = Hash.sha3(eip712.domainName().getBytes(StandardCharsets.UTF_8));
        byte[] versionHash = Hash.sha3(eip712.domainVersion().getBytes(StandardCharsets.UTF_8));
        byte[] chainIdWord = uintWord(BigInteger.valueOf(eip712.chainId()));
        byte[] verifyingContractWord = addressWord(eip712.verifyingContract());

        return Hash.sha3(concat(
                DOMAIN_TYPEHASH,
                nameHash,
                versionHash,
                chainIdWord,
                verifyingContractWord
        ));
    }

    private byte[] readingStructHash(ReadingSubmissionRequest request) {
        long h3 = Long.parseUnsignedLong(request.h3Index().substring(2), 16);
        long timestamp = request.timestamp();
        long epochId = request.epochId();

        return Hash.sha3(concat(
                READING_TYPEHASH,
                addressWord(request.reporter()),
                uintWord(unsignedLongToBigInteger(h3)),
                uintWord(BigInteger.valueOf(asUint32(epochId))),
                uintWord(BigInteger.valueOf(asUint32(timestamp))),
                uintWord(BigInteger.valueOf(request.aqi())),
                uintWord(BigInteger.valueOf(request.pm25())),
                uintWord(BigInteger.valueOf(orZero(request.pm10()))),
                uintWord(BigInteger.valueOf(orZero(request.o3()))),
                uintWord(BigInteger.valueOf(orZero(request.no2()))),
                uintWord(BigInteger.valueOf(orZero(request.so2()))),
                uintWord(BigInteger.valueOf(orZero(request.co())))
        ));
    }

    private long asUint32(long value) {
        if (value < 0 || value > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("Value does not fit uint32");
        }
        return value;
    }

    private SignatureData parseSignature(String signatureHex) {
        byte[] sig = Numeric.hexStringToByteArray(signatureHex);
        if (sig.length != 65) {
            throw new IllegalArgumentException("Invalid signature length");
        }

        byte[] r = Arrays.copyOfRange(sig, 0, 32);
        byte[] s = Arrays.copyOfRange(sig, 32, 64);
        byte[] v = new byte[] {sig[64]};
        return new SignatureData(v, r, s);
    }

    private int toRecoveryId(byte vRaw) {
        int v = vRaw & 0xFF;
        if (v == 27 || v == 28) {
            return v - 27;
        }
        if (v == 0 || v == 1) {
            return v;
        }
        return -1;
    }

    private String normalizeAddress(String address) {
        if (address == null || !address.startsWith("0x") || address.length() != 42) {
            throw new IllegalArgumentException("Invalid address");
        }
        return address.toLowerCase();
    }

    private int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private byte[] addressWord(String address) {
        String normalized = normalizeAddress(address);
        BigInteger value = new BigInteger(normalized.substring(2), 16);
        return uintWord(value);
    }

    private byte[] uintWord(BigInteger value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("Unsigned integer cannot be negative");
        }

        byte[] raw = value.toByteArray();
        if (raw.length == 33 && raw[0] == 0) {
            raw = Arrays.copyOfRange(raw, 1, raw.length);
        }
        if (raw.length > 32) {
            throw new IllegalArgumentException("Unsigned integer too large");
        }

        byte[] out = new byte[32];
        System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    private BigInteger unsignedLongToBigInteger(long value) {
        if (value >= 0) {
            return BigInteger.valueOf(value);
        }
        return BigInteger.valueOf(value & Long.MAX_VALUE).setBit(63);
    }

    private byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }

        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
