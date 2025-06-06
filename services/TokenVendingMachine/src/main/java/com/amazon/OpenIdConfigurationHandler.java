package com.amazon;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenID Configuration endpoint handler for the Token Vending Machine.
 * This endpoint is required for IAM Identity Center to discover the JWKS endpoint.
 */
public class OpenIdConfigurationHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {
            // 1) Get the stage from the request context
            String stage = request.getRequestContext().getStage();  // e.g. "prod"
            
            // 2) Read Host and scheme from headers
            Map<String,String> headers = request.getHeaders();
            String host = headers.get("Host");                      // e.g. "abc123.execute-api.us-east-1.amazonaws.com"
            String proto = headers.getOrDefault("X-Forwarded-Proto", "https");
            
            // 3) Build the issuer URL
            String issuerUrl = proto + "://" + host + "/" + stage;
            context.getLogger().log("Inferred issuer URL: " + issuerUrl);
            
            Map<String, Object> openIdConfig = createOpenIdConfiguration(issuerUrl);

            // --- DEBUG LOGGING ---
            try {
                String debugJson = JSON.writeValueAsString(openIdConfig);
                System.out.println("DEBUG OpenID Configuration: " + debugJson);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                System.out.println("DEBUG failed to serialize OpenID config: " + e.getMessage());
            }

            // Return the successful response
            return createResponse(200, JSON.writeValueAsString(openIdConfig));
        } catch (Exception e) {
            context.getLogger().log("Error generating OpenID Configuration: " + e.getMessage());
            return createErrorResponse(500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Create an OpenID Configuration response.
     * @param issuerUrl The inferred issuer URL from the request context
     */
    private Map<String, Object> createOpenIdConfiguration(String issuerUrl) {
        Map<String, Object> config = new HashMap<>();

        // Required fields for OpenID Configuration
        config.put("issuer", issuerUrl);
        config.put("jwks_uri", issuerUrl + "/.well-known/jwks.json");

        // Additional required fields
        config.put("token_endpoint", issuerUrl + "/token");
        config.put("authorization_endpoint", issuerUrl + "/authorize");
        config.put("userinfo_endpoint", issuerUrl + "/userinfo");

        // Response types - what your TVM supports
        config.put("response_types_supported", new String[]{"id_token", "token"});

        // Subject types - typically "public" for most OIDC providers
        config.put("subject_types_supported", new String[]{"public"});

        // Grant types supported
        config.put("grant_types_supported", new String[]{"implicit", "authorization_code"});

        // ID Token signing algorithms
        config.put("id_token_signing_alg_values_supported", new String[]{"RS256"});

        // Supported scopes
        config.put("scopes_supported", new String[]{"openid", "email", "profile"});

        // Claims that can be returned
        config.put("claims_supported", new String[]{"sub", "iss", "aud", "exp", "iat", "jti", "email",
                "aws:PrincipalTag/Email", "https://aws.amazon.com/tags"});

        return config;
    }

    /**
     * Create a successful API Gateway response.
     */
    private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setBody(body);

        // Set CORS headers
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type");
        response.setHeaders(headers);

        return response;
    }

    /**
     * Create an error response.
     */
    private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String errorMessage) {
        Map<String, String> errorBody = new HashMap<>();
        errorBody.put("error", errorMessage);

        try {
            return createResponse(statusCode, JSON.writeValueAsString(errorBody));
        } catch (Exception e) {
            // Fallback if JSON serialization fails
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(statusCode);
            response.setBody("{\"error\":\"" + errorMessage + "\"}");
            return response;
        }
    }
}