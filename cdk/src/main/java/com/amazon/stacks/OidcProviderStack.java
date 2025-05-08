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
        String issuer   = Fn.importValue("TvmIssuerUrl");
        String audience = Fn.importValue("TvmAudience");

        // 2) Use a comprehensive, pinned list of AWS cert thumbprints (SHA‑1; 20 bytes = 40 hex chars)
        List<String> thumbprints = List.of(
                // CloudFront intermediate CA
                "6938fd4d98bab03faadb97b34396831e3780aea1",
                // Amazon Root CA 1
                "9e99a48a9960b14926bb7f3b02e22da0afd8f4ff",
                // Amazon Root CA 2
                "146f82d739f8743252a613787ee538729a59373e",
                // Amazon Trust Services Root
                "b3f5e77f65955f6e8878eca038736e33c2fffa8c",
                // Starfield Services Root Certificate Authority - G2
                "9e99a48a9960b14926bb7f3b02e22da2b0ab7280"
        );

        // 3) Create the OIDC provider
        this.oidcProvider = OpenIdConnectProvider.Builder.create(this, "TvmIamOidcProvider")
                .url(issuer)
                .clientIds(List.of(audience))
                .thumbprints(thumbprints)
                .build();

        // 4) Export its ARN for downstream stacks
        new CfnOutput(this, "TvmOidcProviderArn", CfnOutputProps.builder()
                .value(this.oidcProvider.getOpenIdConnectProviderArn())
                .description("IAM OIDC Provider ARN")
                .exportName("TvmOidcProviderArn")
                .build());
    }

    public OpenIdConnectProvider getProvider() {
        return this.oidcProvider;
    }
}