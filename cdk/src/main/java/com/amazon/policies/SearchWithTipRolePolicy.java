package com.amazon.policies;

import java.net.URI;
import java.util.List;
import java.util.Map;

import software.constructs.Construct;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.FederatedPrincipal;
import software.amazon.awscdk.services.iam.Effect;

public class SearchWithTipRolePolicy {

    /**
     * @param scope             your CDK Construct scope
     * @param id                logical ID for the Role
     * @param oidcProviderArn   the TVM OIDC provider ARN (exported from TVMStack)
     * @param issuerUrl         the TVM issuer URL (must match your JWT "iss")
     * @param audience          the TVM audience (must match your JWT "aud")
     * @param applicationId     your QBusiness ApplicationId
     */
    public static Role create(
            Construct scope,
            String id,
            String oidcProviderArn,
            String issuerUrl,
            String audience,
            String applicationId
    ) {
        // extract host from issuer, e.g. "xam38kxyjk.execute-api.us-east-1.amazonaws.com"
        String issuerHost = URI.create(issuerUrl).getHost();

        // inline policy granting QBusiness search permission
        PolicyStatement inlineStmt = PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("qbusiness:SearchRelevantContent"))
                .resources(List.of("*"))
                .build();

        // Create the Role
        return Role.Builder.create(scope, id)
                // only your OIDC provider can assume this role via WebIdentity
                .assumedBy(new FederatedPrincipal(
                        oidcProviderArn,
                        Map.of(issuerHost + ":aud", audience),
                        "sts:AssumeRoleWithWebIdentity"
                ))
                // attach the inline search policy
                .inlinePolicies(Map.of(
                        "SearchWithTipInlinePolicy",
                        PolicyDocument.Builder.create()
                                .statements(List.of(inlineStmt))
                                .build()
                ))
                .build();
    }
}