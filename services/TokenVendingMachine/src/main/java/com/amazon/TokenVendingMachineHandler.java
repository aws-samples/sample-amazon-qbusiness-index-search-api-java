package com.amazon;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/**
 * Token Vending Machine (TVM) Lambda handler that issues JWT tokens
 * and serves a /userinfo endpoint for QBusiness, now signing via KMS.
 */
public class TokenVendingMachineHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUDIENCE = System.getenv("AUDIENCE");
    private static final int TOKEN_EXPIRATION_MINUTES = 60;
    private static final String USER_ATTRIBUTE_CLAIM = "email";

    private final KeyManager keyManager;

    public TokenVendingMachineHandler() {
        this.keyManager = KeyManager.getInstance();
        System.out.println("[TVM] Initialized KeyManager with keyId=" + keyManager.getKeyId());
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent req, Context ctx) {
        String path = req.getPath();
        ctx.getLogger().log("Incoming path: " + path);
        try {
            if (path.endsWith("/token")) {
                return handleToken(req, ctx);
            } else if (path.endsWith("/userinfo")) {
                return handleUserInfo(req, ctx);
            } else {
                return error(404, "Unknown path: " + path);
            }
        } catch (Exception e) {
            ctx.getLogger().log("Error in " + path + ": " + e.toString());
            return error(500, "Internal server error");
        }
    }

    private APIGatewayProxyResponseEvent handleToken(APIGatewayProxyRequestEvent req, Context ctx) throws Exception {
        // Infer issuer
        String stage = req.getRequestContext().getStage();
        Map<String,String> headers = req.getHeaders();
        String host = headers.get("Host");
        String proto = headers.getOrDefault("X-Forwarded-Proto", "https");
        String issuerUrl = proto + "://" + host + "/" + stage;
        ctx.getLogger().log("Inferred issuer URL: " + issuerUrl);

        // Parse email
        @SuppressWarnings("unchecked")
        Map<String,String> body = JSON.readValue(req.getBody(), Map.class);
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return error(400, "Missing email parameter");
        }

        // Build JWT header & payload
        Instant now = Instant.now();
        Instant exp = now.plus(TOKEN_EXPIRATION_MINUTES, ChronoUnit.MINUTES);
        Map<String,Object> header = Map.of(
                "alg", "RS256",
                "typ", "JWT",
                "kid", keyManager.getKeyId()
        );
        Map<String,Object> claims = new HashMap<>();
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("sub", UUID.nameUUIDFromBytes(email.getBytes(StandardCharsets.UTF_8)).toString());
        claims.put("iss", issuerUrl);
        claims.put("aud", AUDIENCE);
        claims.put("iat", Date.from(now).getTime() / 1000);
        claims.put("exp", Date.from(exp).getTime() / 1000);
        claims.put(USER_ATTRIBUTE_CLAIM, email);
        claims.put("https://aws.amazon.com/tags", Map.of(
                "principal_tags", Map.of("Email", new String[]{email})
        ));

        // Base64URL encode
        String encodedHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(JSON.writeValueAsString(header).getBytes(StandardCharsets.UTF_8));
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(JSON.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));
        String signingInput = encodedHeader + "." + encodedPayload;

        // Sign via KMS
        byte[] signature = keyManager.sign(signingInput.getBytes(StandardCharsets.UTF_8));
        String encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        String jwt = signingInput + "." + encodedSignature;
        ctx.getLogger().log("Generated JWT (prefix): " + jwt.substring(0, Math.min(10, jwt.length())) + "...");

        return success(JSON.writeValueAsString(Map.of("id_token", jwt)));
    }

    private APIGatewayProxyResponseEvent handleUserInfo(APIGatewayProxyRequestEvent req, Context ctx) throws Exception {
        String auth = req.getHeaders().get("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return error(401, "Missing or invalid Authorization header");
        }
        String jwt = auth.substring(7);
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return error(400, "Invalid JWT format");
        byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
        @SuppressWarnings("unchecked") Map<String,Object> claims = JSON.readValue(decoded, Map.class);

        Map<String,Object> out = new HashMap<>();
        out.put("sub", claims.get("sub"));
        out.put("email", claims.get("email"));
        @SuppressWarnings("unchecked") Map<String,Object> awsTags = (Map<String,Object>) claims.get("https://aws.amazon.com/tags");
        if (awsTags != null) out.put("https://aws.amazon.com/tags", awsTags);

        return success(JSON.writeValueAsString(out));
    }

    private APIGatewayProxyResponseEvent success(String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody(body)
                .withHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", "*",
                        "Access-Control-Allow-Methods", "GET,POST,OPTIONS",
                        "Access-Control-Allow-Headers", "Authorization,Content-Type"
                ));
    }

    private APIGatewayProxyResponseEvent error(int code, String msg) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(code)
                .withBody("{\"error\":\""+msg+"\"}")
                .withHeaders(Map.of("Content-Type","application/json"));
    }
}