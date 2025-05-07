package com.amazon.stacks;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.Fn;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;

import software.amazon.awscdk.services.iam.OpenIdConnectProvider;

import java.util.List;

/**
 * Stack that creates an OIDC provider based on TVM outputs.
 * This breaks the circular dependency by separating the OIDC provider from the TVM stack.
 */
public class OidcProviderStack extends Stack {
    private final OpenIdConnectProvider oidcProvider;

    public OidcProviderStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public OidcProviderStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // 1) Import exactly the export names from TVM stack
        String issuer = Fn.importValue("TvmIssuerUrl");
        String audience = Fn.importValue("TvmAudience");

        List<String> thumbprints = List.of("9e99a48a9960b14926bb7f3b02e22da0afd8f4f");

        // 2) Create the OIDC provider
        this.oidcProvider = OpenIdConnectProvider.Builder.create(this, "TvmIamOidcProvider")
                .url(issuer)
                .clientIds(List.of(audience))
                .thumbprints(thumbprints)
                .build();

        // 3) Re-export its ARN for downstream stacks
        new CfnOutput(this, "TvmOidcProviderArn", CfnOutputProps.builder()
                .value(this.oidcProvider.getOpenIdConnectProviderArn())
                .description("IAM OIDC Provider ARN")
                .exportName("TvmOidcProviderArn")
                .build());
    }

    // Getter for the OIDC provider
    public OpenIdConnectProvider getProvider() {
        return this.oidcProvider;
    }
}