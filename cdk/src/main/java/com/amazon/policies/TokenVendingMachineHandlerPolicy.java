// services/tvm/src/main/java/com/amazon/policies/TokenVendingMachineHandlerPolicy.java
package com.amazon.policies;

import java.util.List;

import software.constructs.Construct;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Effect;

public final class TokenVendingMachineHandlerPolicy {
    private TokenVendingMachineHandlerPolicy() {}

    public static PolicyDocument create(Construct scope, String dynamoDbTableArn) {
        // We'll get the key ARN passed from the stack
        // The specific key ARN will be granted separately using key.grant() in the stack
        // This is a placeholder policy that will be scoped by the grant
        PolicyStatement kmsSign = PolicyStatement.Builder.create()
                .sid("AllowKmsSign")
                .actions(List.of("kms:Sign"))
                .resources(List.of("*"))  // Scoped by the key.grant() call in the stack
                .effect(Effect.ALLOW)
                .build();

        // DynamoDB permissions for email allowlist
        PolicyStatement dynamoDbRead = PolicyStatement.Builder.create()
                .sid("AllowDynamoDbRead")
                .actions(List.of(
                        "dynamodb:GetItem",
                        "dynamodb:Query"
                ))
                .resources(List.of(dynamoDbTableArn))
                .effect(Effect.ALLOW)
                .build();

        return PolicyDocument.Builder.create()
                .statements(List.of(kmsSign, dynamoDbRead))
                .build();
    }
}