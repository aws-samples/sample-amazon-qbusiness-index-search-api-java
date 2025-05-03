package com.amazon;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Token Vending Machine (TVM) Lambda handler that issues JWT tokens
 * and serves a /userinfo endpoint for QBusiness.
 */
public class TokenVendingMachineHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final ObjectMapper JSON = new ObjectMapper();

    // Configuration (move into ENV vars in prod)
    private static final String ISSUER_URL  = System.getenv("ISSUER_URL");
    private static final String AUDIENCE    = System.getenv("AUDIENCE");
    private static final int    TOKEN_EXPIRATION_MINUTES = 60;

    // Claim names
    private static final String USER_ATTRIBUTE_CLAIM    = "email";
    private static final String AWS_PRINCIPAL_TAG_EMAIL = "aws:PrincipalTag/Email";

    private final KeyManager keyManager = KeyManager.getInstance();

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
        // parse incoming body
        @SuppressWarnings("unchecked")
        Map<String,String> body = JSON.readValue(req.getBody(), Map.class);
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return error(400, "Missing email parameter");
        }

        // build JWT
        Instant now = Instant.now();
        Instant exp = now.plus(TOKEN_EXPIRATION_MINUTES, ChronoUnit.MINUTES);
        String jti = UUID.randomUUID().toString();
        String sub = UUID.nameUUIDFromBytes(email.getBytes(StandardCharsets.UTF_8)).toString();

        JwtBuilder b = Jwts.builder()
                .setId(jti)
                .setSubject(sub)
                .setIssuer(ISSUER_URL)
                .setAudience(AUDIENCE)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(exp))
                .claim(USER_ATTRIBUTE_CLAIM, email)
                .claim("https://aws.amazon.com/tags", Map.of(
                        "principal_tags", Map.of(
                                "Email", new String[]{email}
                        )
                ))
                .setHeaderParam("kid", keyManager.getKeyId())
                .signWith(keyManager.getKeyPair().getPrivate());

        String token = b.compact();
        ctx.getLogger().log("Generated JWT (prefix): " + token.substring(0, Math.min(10, token.length())) + "...");

        Map<String,String> resp = new HashMap<>();
        resp.put("id_token", token);
        return success(JSON.writeValueAsString(resp));
    }

    private APIGatewayProxyResponseEvent handleUserInfo(APIGatewayProxyRequestEvent req, Context ctx) throws Exception {
        String auth = req.getHeaders().get("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return error(401, "Missing or invalid Authorization header");
        }
        String jwt = auth.substring("Bearer ".length());

        // decode payload
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            return error(400, "Invalid JWT format");
        }
        byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
        @SuppressWarnings("unchecked")
        Map<String,Object> claims = JSON.readValue(decoded, Map.class);

        // return only the keys QBusiness cares about
        Map<String,Object> out = new HashMap<>();
        out.put("sub", claims.get("sub"));
        out.put("email", claims.get("email"));

        @SuppressWarnings("unchecked")
        Map<String,Object> awsTags = (Map<String,Object>) claims.get("https://aws.amazon.com/tags");
        if (awsTags != null) {
            out.put("https://aws.amazon.com/tags", awsTags);
        }

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
        String body = "{\"error\":\""+msg+"\"}";
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(code)
                .withBody(body)
                .withHeaders(Map.of("Content-Type","application/json"));
    }
}