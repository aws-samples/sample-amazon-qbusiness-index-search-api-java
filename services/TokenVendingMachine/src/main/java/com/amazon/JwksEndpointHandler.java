package com.amazon;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JWKS (JSON Web Key Set) endpoint handler for the Token Vending Machine.
 * This endpoint is required for IAM Identity Center to verify the signature of JWT tokens.
 */
public class JwksEndpointHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final ObjectMapper JSON = new ObjectMapper();

    // Key manager for JWT signing keys
    private final KeyManager keyManager;

    public JwksEndpointHandler() {
        // Initialize the key manager (reads KEY_ID from environment internally)
        keyManager = KeyManager.getInstance();
        System.out.println(">>> keyId=" + keyManager.getKeyId());
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {
            // Get the public key
            String publicKeyBase64 = keyManager.getPublicKeyBase64();

            // Decode the public key
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(keySpec);

            // Create the JWKS response
            Map<String, Object> jwksResponse = createJwksResponse(publicKey);

            // Return the successful response
            return createResponse(200, JSON.writeValueAsString(jwksResponse));
        } catch (Exception e) {
            context.getLogger().log("Error generating JWKS: " + e.getMessage());
            e.printStackTrace();
            return createErrorResponse(500, "Internal server error: " + e.getMessage());
        }
    }

    private Map<String, Object> createJwksResponse(RSAPublicKey publicKey) {
        Map<String, Object> jwk = new HashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", keyManager.getKeyId());

        byte[] modulusBytes = publicKey.getModulus().toByteArray();
        if (modulusBytes[0] == 0) {
            byte[] tmp = new byte[modulusBytes.length - 1];
            System.arraycopy(modulusBytes, 1, tmp, 0, tmp.length);
            modulusBytes = tmp;
        }

        byte[] exponentBytes = publicKey.getPublicExponent().toByteArray();
        if (exponentBytes[0] == 0) {
            byte[] tmp = new byte[exponentBytes.length - 1];
            System.arraycopy(exponentBytes, 1, tmp, 0, tmp.length);
            exponentBytes = tmp;
        }

        jwk.put("n", Base64.getUrlEncoder().withoutPadding().encodeToString(modulusBytes));
        jwk.put("e", Base64.getUrlEncoder().withoutPadding().encodeToString(exponentBytes));

        List<Map<String, Object>> keys = new ArrayList<>();
        keys.add(jwk);

        Map<String, Object> jwksResponse = new HashMap<>();
        jwksResponse.put("keys", keys);
        return jwksResponse;
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setBody(body);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type");
        headers.put("Cache-Control", "public, max-age=86400");
        response.setHeaders(headers);

        return response;
    }

    private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String errorMessage) {
        try {
            Map<String, String> errorBody = new HashMap<>();
            errorBody.put("error", errorMessage);
            return createResponse(statusCode, JSON.writeValueAsString(errorBody));
        } catch (Exception e) {
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(statusCode);
            response.setBody("{\"error\":\"" + errorMessage + "\"}");
            return response;
        }
    }
}
