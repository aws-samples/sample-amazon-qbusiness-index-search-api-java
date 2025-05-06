package com.amazon.policies;

import java.net.URI;
import java.util.List;
import java.util.Map;

import software.constructs.Construct;
import software.amazon.awscdk.Stack;
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
        // During CDK synthesis, we need to avoid parsing the token as a URI
        // Use a placeholder value that will be replaced at synth time
        String issuerHost = "execute-api.${AWS::Region}.amazonaws.com";

        // inline policy granting QBusiness search permission
        // Create a specific resource ARN for the QBusiness application
        String qbusinessAppArn = "arn:aws:qbusiness:" + Stack.of(scope).getRegion() + ":" + 
                                  Stack.of(scope).getAccount() + ":application/" + applicationId;
                                  
        PolicyStatement inlineStmt = PolicyStatement.Builder.create()
                .sid("AllowQBusinessSearch")
                .effect(Effect.ALLOW)
                .actions(List.of("qbusiness:SearchRelevantContent"))
                .resources(List.of(qbusinessAppArn))
                .build();

        // Create the Role
        return Role.Builder.create(scope, id)
                // only your OIDC provider can assume this role via WebIdentity
                .assumedBy(new FederatedPrincipal(
                        oidcProviderArn,
                        Map.of(
                            "StringEquals", 
                            Map.of(issuerHost + ":aud", audience)
                        ),
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