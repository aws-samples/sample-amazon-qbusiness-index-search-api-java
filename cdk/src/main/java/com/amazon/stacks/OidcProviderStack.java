package com.amazon.stacks;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Aspects;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.Fn;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;
import io.github.cdklabs.cdknag.AwsSolutionsChecks;

import software.amazon.awscdk.services.iam.OpenIdConnectProvider;
import software.amazon.awscdk.services.iam.IOpenIdConnectProvider;

import java.util.List;

/**
 * Stack that creates an OIDC provider based on TVM outputs.
 * This breaks the circular dependency by separating the OIDC provider from the TVM stack.
 */
public class OidcProviderStack extends Stack {
    private final IOpenIdConnectProvider oidcProvider;

    public OidcProviderStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public OidcProviderStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // Import values from TokenVendingMachineStack
        String issuerUrl = Fn.importValue("TvmIssuerUrl");
        String audience = Fn.importValue("TvmAudience");

        // Thumbprint of the TVM's TLS cert
        List<String> thumbprints = List.of("9e99a48a9960b14926bb7f3b02e22da0afd8f4f");

        // Create the OIDC provider
        this.oidcProvider = OpenIdConnectProvider.Builder.create(this, "TvmIamOidcProvider")
                .url(issuerUrl)
                .clientIds(List.of(audience))
                .thumbprints(thumbprints)
                .build();

        // Export the OIDC provider ARN for downstream stacks
        new CfnOutput(this, "OidcProviderArn", CfnOutputProps.builder()
                .value(this.oidcProvider.getOpenIdConnectProviderArn())
                .description("IAM OIDC Provider ARN")
                .exportName("OidcProviderArn")
                .build());
    }

    // Getter for the OIDC provider
    public IOpenIdConnectProvider getOidcProvider() {
        return this.oidcProvider;
    }
}