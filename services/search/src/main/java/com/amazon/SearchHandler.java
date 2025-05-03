package com.amazon;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.qbusiness.QBusinessClient;
import software.amazon.awssdk.services.qbusiness.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lambda handler for /search
 */
@SuppressWarnings("unused")
public class SearchHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger log = LoggerFactory.getLogger(SearchHandler.class);
    private static final Region REGION    = Region.US_EAST_1;
    private static final ObjectMapper JSON = new ObjectMapper();

    // injected via Lambda ENV
    private static final String TOKEN_ENDPOINT = System.getenv("TOKEN_ENDPOINT");
    private static final String ROLE_ARN       = System.getenv("ROLE_ARN");
    private static final String APP_ID         = System.getenv("QBUS_APP_ID");
    private static final String RETRIEVER_ID   = System.getenv("QBUS_RETRIEVER_ID");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context ctx) {
        try {
            logEnv();
            ctx.getLogger().log("Input event → " + JSON.writeValueAsString(event));

            // Parse the request body
            String body = event.getBody();
            Map<String, String> payload = JSON.readValue(body, new TypeReference<>() {
            });

            String email       = payload.get("email");
            String query       = payload.getOrDefault("query", "hello");
            String application = payload.getOrDefault("applicationId", APP_ID);
            String retriever   = payload.getOrDefault("retrieverId", RETRIEVER_ID);

            validate(email, application, retriever);

            ctx.getLogger().log("Fetching STS creds for user: " + email);
            Map<String,String> creds = getStsCreds(email, ctx);

            ctx.getLogger().log("Building QBusinessClient with new creds");
            try (QBusinessClient q = createQClient(creds)) {
                String result = doSearch(q, query, application, retriever);
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withBody(result)
                        .withHeaders(Map.of("Content-Type", "application/json"));
            }

        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody(onError(e))
                    .withHeaders(Map.of("Content-Type", "application/json"));
        }
    }

    private void logEnv() {
        System.out.println("ENV → TOKEN_ENDPOINT=" + safe(TOKEN_ENDPOINT)
                + ", ROLE_ARN=" + safe(ROLE_ARN)
                + ", QBUS_APP_ID=" + safe(APP_ID)
                + ", QBUS_RETRIEVER_ID=" + safe(RETRIEVER_ID));
    }
    private String safe(String s) { return s==null?"<unset>":s; }

    private void validate(String email, String app, String ret) {
        if (email==null||email.isBlank()) throw new IllegalArgumentException("email is required");
        if (app  ==null||app .length()<36) throw new IllegalArgumentException("applicationId invalid");
        if (ret  ==null||ret .length()<36) throw new IllegalArgumentException("retrieverId invalid");
    }

    private Map<String,String> getStsCreds(String email, Context ctx) throws Exception {
        // 1) fetch id_token
        String idToken = fetchIdToken(email, ctx);

        // 2) use AWS SDK STS client to AssumeRoleWithWebIdentity
        try (StsClient sts = StsClient.builder()
                .region(REGION)
                .build()) {
            AssumeRoleWithWebIdentityResponse stsResp =
                    sts.assumeRoleWithWebIdentity(r -> r
                            .roleArn(ROLE_ARN)
                            .roleSessionName("session-" + email)
                            .webIdentityToken(idToken));

            var c = stsResp.credentials();
            return Map.of(
                    "aws_access_key_id",     c.accessKeyId(),
                    "aws_secret_access_key", c.secretAccessKey(),
                    "aws_session_token",     c.sessionToken()
            );
        }
    }

    private String fetchIdToken(String email, Context ctx) throws Exception {
        String url = TOKEN_ENDPOINT.startsWith("http")
                ? TOKEN_ENDPOINT
                : "https://" + TOKEN_ENDPOINT;
        String body = JSON.writeValueAsString(Map.of("email",email));
        ctx.getLogger().log("TVM → POST " + url + "   body=" + body);
        HttpRequest r = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = httpClient.send(r, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode()!=200) throw new IOException("TVM failed: "+resp.body());
        String tok = (String) JSON.readValue(resp.body(), Map.class).get("id_token");

        // Debug: Decode and examine the JWT token
        try {
            String[] parts = tok.split("\\.");
            if (parts.length >= 2) {
                String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                ctx.getLogger().log("JWT payload: " + payload);
            }
        } catch (Exception e) {
            ctx.getLogger().log("Failed to decode JWT: " + e.getMessage());
        }

        ctx.getLogger().log("TVM returned id_token (first 20): " + tok.substring(0,20)+"...");
        return tok;
    }

    private QBusinessClient createQClient(Map<String,String> c) {
        AwsCredentialsProvider p = StaticCredentialsProvider.create(
                AwsSessionCredentials.create(
                        c.get("aws_access_key_id"),
                        c.get("aws_secret_access_key"),
                        c.get("aws_session_token")
                )
        );
        SdkHttpClient http = ApacheHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(30))
                .socketTimeout(Duration.ofSeconds(60))
                .build();

        return QBusinessClient.builder()
                .region(REGION)
                .httpClient(http)
                .credentialsProvider(p)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(60))
                        .build())
                .build();
    }

    private String doSearch(QBusinessClient client, String q, String appId, String retId) throws Exception {
        SearchRelevantContentRequest req = SearchRelevantContentRequest.builder()
                .applicationId(appId)
                .queryText(q)
                .contentSource(ContentSource.builder()
                        .retriever(RetrieverContentSource.builder()
                                .retrieverId(retId)
                                .build())
                        .build())
                .maxResults(5)
                .build();

        SearchRelevantContentResponse resp = client.searchRelevantContent(req);
        if (resp.relevantContent() == null || resp.relevantContent().isEmpty()) {
            return JSON.writeValueAsString(Map.of("results", "<none>"));
        }
        Map<String, Object> result = buildResultMap(resp);
        return JSON.writeValueAsString(result);
    }

    private Map<String,Object> buildResultMap(SearchRelevantContentResponse resp) {
        List<Map<String,Object>> list = new ArrayList<>();
        for (RelevantContent c : resp.relevantContent()) {
            Map<String,Object> m = new HashMap<>();
            if (c.documentTitle() != null) m.put("title",   c.documentTitle());
            if (c.documentUri()   != null) m.put("uri",     c.documentUri());
            if (c.content()       != null) m.put("snippet", c.content());
            list.add(m);
        }
        Map<String,Object> result = new HashMap<>();
        result.put("relevantContent", list);
        result.put("nextToken",        resp.nextToken());
        return result;
    }
    private String onError(Exception e) {
        log.error("Unhandled exception in SearchHandler", e);
        try {
            return JSON.writeValueAsString(Map.of(
                    "errorType",    e.getClass().getSimpleName(),
                    "errorMessage", e.getMessage()
            ));
        } catch (Exception ex) {
            log.error("Failed to serialize error response", ex);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}