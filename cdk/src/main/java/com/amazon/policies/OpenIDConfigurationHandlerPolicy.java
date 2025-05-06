// services/tvm/src/main/java/com/amazon/policies/OpenIDConfigurationHandlerPolicy.java
package com.amazon.policies;

import software.constructs.Construct;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Effect;
import java.util.List;

/**
 * No extra AWS permissions required beyond AWSLambdaBasicExecutionRole,
 * but we need to create a valid policy document.
 */
public final class OpenIDConfigurationHandlerPolicy {
    private OpenIDConfigurationHandlerPolicy() {}

    public static PolicyDocument create(Construct scope) {
        // Create a simple statement to make it a valid policy document
        PolicyStatement noOpStatement = PolicyStatement.Builder.create()
                .sid("NoExtraPermissionsNeeded")
                .effect(Effect.ALLOW)
                .actions(List.of("logs:CreateLogGroup"))
                .resources(List.of("*"))
                .build();
                
        return PolicyDocument.Builder.create()
                .statements(List.of(noOpStatement))
                .build();
    }
}