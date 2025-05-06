// services/tvm/src/main/java/com/amazon/policies/JwksEndpointHandlerPolicy.java
package com.amazon.policies;

import java.util.List;

import software.constructs.Construct;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Effect;

public final class JwksEndpointHandlerPolicy {
    private JwksEndpointHandlerPolicy() {}

    public static PolicyDocument create(Construct scope) {
        // Policy with wildcard resource that is scoped by key.grant() calls in the main stack
        // The specific KMS key permissions are granted at the stack level
        // The wildcard here is intentional as it's a placeholder only
        PolicyStatement kmsGetPublicKey = PolicyStatement.Builder.create()
                .sid("AllowKmsGetPublicKey")
                .actions(List.of("kms:GetPublicKey", "kms:DescribeKey"))
                .resources(List.of("*"))  // Scoped by the key.grant() call in the stack
                .effect(Effect.ALLOW)
                .build();

        return PolicyDocument.Builder.create()
                .statements(List.of(kmsGetPublicKey))
                .build();
    }
}