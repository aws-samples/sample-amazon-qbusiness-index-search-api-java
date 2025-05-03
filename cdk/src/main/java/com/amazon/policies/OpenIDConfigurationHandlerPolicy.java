// services/tvm/src/main/java/com/amazon/policies/OpenIDConfigurationHandlerPolicy.java
package com.amazon.policies;

import software.constructs.Construct;
import software.amazon.awscdk.services.iam.PolicyDocument;

/**
 * No extra AWS permissions required beyond AWSLambdaBasicExecutionRole,
 * so this returns an empty document.
 */
public final class OpenIDConfigurationHandlerPolicy {
    private OpenIDConfigurationHandlerPolicy() {}

    public static PolicyDocument create(Construct scope) {
        return PolicyDocument.Builder.create().build();
    }
}