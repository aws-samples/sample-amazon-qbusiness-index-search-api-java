package com.amazon;

import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;
import software.amazon.awssdk.services.kms.model.GetPublicKeyResponse;
import software.amazon.awssdk.services.kms.model.SignRequest;
import software.amazon.awssdk.services.kms.model.SignResponse;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.MessageDigest;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * Key Manager using AWS KMS for signing and public key retrieval.
 */
public class KeyManager {
    private static final ObjectMapper JSON = new ObjectMapper();

    // Environment variable pointing to the Secrets Manager secret storing just the keyId
    private static final String SECRET_NAME = System.getenv("KEY_SECRET_NAME");

    private static KeyManager instance;
    private final String keyId;
    private final PublicKey publicKey;
    private final KmsClient kmsClient;
    private final SecretsManagerClient secretsClient;

    private KeyManager() {
        if (SECRET_NAME == null || SECRET_NAME.isEmpty()) {
            throw new IllegalStateException("KEY_SECRET_NAME environment variable is not set");
        }
        this.secretsClient = SecretsManagerClient.create();
        Map<String, String> secretMap = loadSecret();
        this.keyId = secretMap.get("keyId");
        if (this.keyId == null || this.keyId.isEmpty()) {
            throw new IllegalStateException("Secret did not contain a 'keyId'");
        }
        this.kmsClient = KmsClient.create();
        this.publicKey = loadPublicKey();
    }

    public static synchronized KeyManager getInstance() {
        if (instance == null) {
            instance = new KeyManager();
        }
        return instance;
    }

    private Map<String, String> loadSecret() {
        GetSecretValueRequest req = GetSecretValueRequest.builder()
                .secretId(SECRET_NAME)
                .build();
        GetSecretValueResponse resp = secretsClient.getSecretValue(req);
        try {
            return JSON.readValue(resp.secretString(), Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse secret string", e);
        }
    }

    private PublicKey loadPublicKey() {
        GetPublicKeyRequest pkReq = GetPublicKeyRequest.builder()
                .keyId(keyId)
                .build();
        GetPublicKeyResponse pkResp = kmsClient.getPublicKey(pkReq);
        byte[] derBytes = pkResp.publicKey().asByteArray();
        try {
            X509EncodedKeySpec spec = new X509EncodedKeySpec(derBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(spec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct public key from KMS response", e);
        }
    }

    /**
     * Sign the given data using KMS RSASSA_PKCS1_V1_5_SHA_256.
     * @param data the raw bytes to sign (will be SHA-256 hashed internally)
     * @return the signature bytes
     */
    public byte[] sign(byte[] data) {
        try {
            // Compute SHA-256 digest
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            // Call KMS Sign
            SignRequest signReq = SignRequest.builder()
                    .keyId(keyId)
                    .message(SdkBytes.fromByteArray(digest))
                    .messageType(MessageType.DIGEST)
                    .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256)
                    .build();
            SignResponse signResp = kmsClient.sign(signReq);
            return signResp.signature().asByteArray();
        } catch (Exception e) {
            throw new RuntimeException("KMS signing failed", e);
        }
    }

    /**
     * Get the public key in standard Base64 encoding.
     */
    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * Get the KMS key ID.
     */
    public String getKeyId() {
        return keyId;
    }
}