package com.amazon;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Key Manager class for handling JWT signing keys.
 * In production, this uses AWS Secrets Manager for key management.
 */
public class KeyManager {

    private static final ObjectMapper JSON = new ObjectMapper();

    // Loaded from environment
    private static final String SECRET_NAME = System.getenv("KEY_SECRET_NAME");
    private static final String REGION      = System.getenv("AWS_REGION");

    private static KeyManager instance;
    private KeyPair keyPair;
    private String  keyId;

    private KeyManager() {
        initializeKeys();
    }

    public static synchronized KeyManager getInstance() {
        if (instance == null) {
            instance = new KeyManager();
        }
        return instance;
    }

    /**
     * Initialize by strictly loading from Secrets Manager.
     * Throws if KEY_SECRET_NAME is missing or load fails.
     */
    private void initializeKeys() {
        if (SECRET_NAME == null || SECRET_NAME.isEmpty()) {
            throw new IllegalStateException("KEY_SECRET_NAME environment variable is not set");
        }
        loadKeysFromSecretsManager();
    }

    /**
     * Load the keyPair and keyId from AWS Secrets Manager.
     */
    private void loadKeysFromSecretsManager() {
        try {
            // Debug logs
            System.out.println(">>> KEY_SECRET_NAME=" + SECRET_NAME);
            System.out.println(">>> AWS_REGION=" + (REGION != null && !REGION.isEmpty() ? REGION : "<default>"));

            // Build Secrets Manager client, override region only if provided
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(REGION != null && !REGION.isEmpty() ? Region.of(REGION) : null)
                    .build();

            // Fetch the secret
            System.out.println(">>> loading secret id=" + SECRET_NAME);
            GetSecretValueRequest req = GetSecretValueRequest.builder()
                    .secretId(SECRET_NAME)
                    .build();
            GetSecretValueResponse result = client.getSecretValue(req);
            String secretString = result.secretString();
            Map<String,String> secretMap = JSON.readValue(secretString, Map.class);

            // Parse keys
            String privB64 = secretMap.get("privateKey");
            String pubB64  = secretMap.get("publicKey");
            keyId = secretMap.get("keyId");

            // Convert PEM format to PKCS8/X509 format by stripping headers/footers
            byte[] privBytes = pemToEncoded(privB64);
            byte[] pubBytes = pemToEncoded(pubB64);

            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey privKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
            PublicKey pubKey = kf.generatePublic(new X509EncodedKeySpec(pubBytes));
            keyPair = new KeyPair(pubKey, privKey);

            System.out.println(">>> loaded keyId=" + keyId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load keys from Secrets Manager", e);
        }
    }


    /**
     * Convert PEM format to raw DER binary by:
     * 1. Base64 decode the PEM string
     * 2. Remove the PEM headers and footers (BEGIN/END lines)
     * 3. Re-Base64 decode to get the raw key bytes
     */
    private byte[] pemToEncoded(String pemBase64) throws Exception {
        // First decode the Base64 content
        String pemContent = new String(Base64.getDecoder().decode(pemBase64));

        // Extract only the Base64 content between the header and footer
        String[] lines = pemContent.split("\n");
        StringBuilder base64Content = new StringBuilder();

        // Skip first and last lines (headers)
        for (int i = 1; i < lines.length - 1; i++) {
            base64Content.append(lines[i]);
        }

        // Decode the inner content to get the actual key bytes
        return Base64.getDecoder().decode(base64Content.toString());
    }

    /**
     * (Optional) Generate & store a new key if you ever need it.
     * Not invoked in production.
     */
    @SuppressWarnings("unused")
    private void generateAndStoreKeys() {
        try {
            keyPair = Keys.keyPairFor(SignatureAlgorithm.RS256);
            keyId   = UUID.randomUUID().toString();

            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(REGION != null && !REGION.isEmpty() ? Region.of(REGION) : null)
                    .build();

            String privB64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
            String pubB64  = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            Map<String,String> map = new HashMap<>();
            map.put("privateKey", privB64);
            map.put("publicKey", pubB64);
            map.put("keyId", keyId);
            
            String secretString = JSON.writeValueAsString(map);
            client.putSecretValue(PutSecretValueRequest.builder()
                    .secretId(SECRET_NAME)
                    .secretString(secretString)
                    .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate/store new keys", e);
        }
    }

    /**
     * Get the key pair.
     */
    public KeyPair getKeyPair() {
        return keyPair;
    }

    /**
     * Get the key ID.
     */
    public String getKeyId() {
        return keyId;
    }

    /**
     * Get the public key encoded as standard Base64.
     */
    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }
}