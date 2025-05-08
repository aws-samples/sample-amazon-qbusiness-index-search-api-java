package com.amazon.policies;

import java.net.URI;
import java.util.List;
import java.util.Map;

import software.constructs.Construct;
import software.amazon.awscdk.CfnJson;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.FederatedPrincipal;
import software.amazon.awscdk.services.iam.CfnRole;
import software.amazon.awscdk.services.iam.Effect;
import io.github.cdklabs.cdknag.NagSuppressions;
import io.github.cdklabs.cdknag.NagPackSuppression;

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
        // Extract issuer host from the issuer URL
        String issuerHost;
        try {
            URI uri = new URI(issuerUrl);
            issuerHost = uri.getHost();
            System.out.println("Using issuer host: " + issuerHost);
        } catch (Exception e) {
            // Fallback to a manual parse if URI parsing fails
            issuerHost = issuerUrl.replace("https://", "").replace("http://", "").split("/")[0];
            System.out.println("Fallback to issuer host: " + issuerHost);
        }

        // Create resource ARN for the QBusiness application
        String qbusinessAppArn = "arn:aws:qbusiness:" + Stack.of(scope).getRegion() + ":" + 
                                  Stack.of(scope).getAccount() + ":application/" + applicationId;
        
        // Create the search permission policy document
        PolicyDocument searchPolicy = PolicyDocument.Builder.create()
                .statements(List.of(
                        PolicyStatement.Builder.create()
                                .sid("AllowQBusinessSearch")
                                .effect(Effect.ALLOW)
                                .actions(List.of("qbusiness:SearchRelevantContent"))
                                .resources(List.of(qbusinessAppArn))
                                .build(),
                        // Allow creating subscription claims - required with auto-subscribe enabled
                        // Note: AWS User Subscriptions does not support resource-level permissions
                        // so we must use "*" but apply strict conditions
                        PolicyStatement.Builder.create()
                                .sid("AllowUserSubscriptionClaim")
                                .effect(Effect.ALLOW)
                                .actions(List.of("user-subscriptions:CreateClaim"))
                                .resources(List.of("*"))
                                .conditions(Map.of(
                                    // Only allow creating claims for self
                                    "Bool", Map.of(
                                        "user-subscriptions:CreateForSelf", "true"
                                    ),
                                    // Only allow claims when called via Q Business
                                    "StringEquals", Map.of(
                                        "aws:CalledViaLast", "qbusiness.amazonaws.com"
                                    )
                                ))
                                .build()
                ))
                .build();

        // Instead of parsing the URI at synthesis time, we'll create a function that does this at deployment time
        
        // Create a CfnJson for the StringEquals condition - this delays resolution to deploy time
        CfnJson audienceCondition = CfnJson.Builder.create(scope, id + "Cond")
            .value(Map.of(
                // We'll format the key as: c8kx5rc81k.execute-api.us-east-1.amazonaws.com/prod:aud
                // This uses a Fn::Join to construct it at deployment time
                software.amazon.awscdk.Fn.join("", List.of(
                    software.amazon.awscdk.Fn.select(1, software.amazon.awscdk.Fn.split("://", issuerUrl)), // Remove https:// prefix
                    ":aud" // Append :aud
                )),
                audience
            ))
            .build();

        // 2) Re-assemble your trust policy, pointing the Condition to that CfnJson
        Map<String, Object> trustPolicy = Map.of(
            "Version", "2012-10-17",
            "Statement", List.of(
                Map.of(
                    "Effect", "Allow",
                    "Principal", Map.of("Service", "qbusiness.amazonaws.com"),
                    "Action", List.of("sts:AssumeRole", "sts:SetContext")
                ),
                Map.of(
                    "Effect", "Allow",
                    "Principal", Map.of("Federated", oidcProviderArn),
                    "Action", List.of("sts:AssumeRoleWithWebIdentity", "sts:TagSession"),
                    "Condition", Map.of(
                        "StringEquals",
                        /* use the CfnJson token here instead of a plain Map */
                        audienceCondition.getValue()
                    )
                )
            )
        );

        // Create a Role using the L2 Builder (will have a dummy trust policy for now)
        Role role = Role.Builder.create(scope, id)
                .inlinePolicies(Map.of("SearchWithTipInlinePolicy", searchPolicy))
                .assumedBy(new FederatedPrincipal(oidcProviderArn)) // This is just a temporary value that will be overridden
                .build();
                
        // Get the underlying CfnRole resource and override the trust policy
        var cfnRole = (CfnRole) role.getNode().getDefaultChild();
        cfnRole.setAssumeRolePolicyDocument(trustPolicy);
        
        // Add suppressions for the wildcard resource in user-subscriptions:CreateClaim permission
        // This is required as the AWS User Subscriptions service does not support resource-level permissions
        NagSuppressions.addResourceSuppressions(role, 
            List.of(NagPackSuppression.builder()
                .id("AwsSolutions-IAM5")
                .reason("AWS User Subscriptions service requires '*' resource for CreateClaim; mitigated with strict conditions")
                .build()), 
            true);
        
        return role;
    }
}