// services/tvm/src/main/java/com/amazon/policies/TokenVendingMachineHandlerPolicy.java
package com.amazon.policies;

import java.util.List;

import software.constructs.Construct;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.Arn;
import software.amazon.awscdk.ArnComponents;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Effect;

public final class TokenVendingMachineHandlerPolicy {
    private TokenVendingMachineHandlerPolicy() {}

    public static PolicyDocument create(Construct scope) {
        String secretName = System.getenv("KEY_SECRET_NAME");
        String secretArn = Arn.format(ArnComponents.builder()
                        .service("secretsmanager")
                        .resource("secret")
                        .resourceName(secretName)
                        .build(),
                Stack.of(scope));

        PolicyStatement getSecret = PolicyStatement.Builder.create()
                .actions(List.of("secretsmanager:GetSecretValue"))
                .resources(List.of(secretArn))
                .effect(Effect.ALLOW)
                .build();

        return PolicyDocument.Builder.create()
                .statements(List.of(getSecret))
                .build();
    }
}