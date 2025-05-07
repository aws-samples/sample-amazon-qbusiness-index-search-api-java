package com.amazon.stacks;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Aspects;
import io.github.cdklabs.cdknag.AwsSolutionsChecks;

/**
 * Main CDK application that creates all stacks.
 */
public class QBusinessApp {
    public static void main(final String[] args) {
        App app = new App();

        // Apply AWS Solutions security checks to all constructs in the app
        Aspects.of(app).add(new AwsSolutionsChecks());

        // 1) TVM API → outputs TvmApiUrl & TvmAudience
        TokenVendingMachineStack tvm = new TokenVendingMachineStack(app, "TokenVendingMachineStack");

        // 2) Build a standalone OIDC provider stack (depends on TVM outputs)
        OidcProviderStack oidc = new OidcProviderStack(app, "OidcProviderStack");
        oidc.addDependency(tvm);

        // 3) QBusiness stack consumes the OIDC provider
        QBusinessStack qbus = new QBusinessStack(
                app,
                "QBusinessStack",
                oidc.getProvider()           // OpenIdConnectProvider returned by OidcProviderStack
        );
        qbus.addDependency(oidc);

        // 4) Search stack consumes TVM outputs and QBusiness outputs
        SearchStack search = new SearchStack(
                app,
                "SearchStack",
                oidc.getProvider(),         // same OIDC provider
                tvm.getApi().getUrl(),      // e.g. https://xyz.execute-api.us-east-1.amazonaws.com/prod/
                tvm.getAudience(),          // “qbusiness-audience”
                qbus.getApplicationId(),    // from QBusinessStack
                qbus.getRetrieverId()       // from QBusinessStack
        );
        search.addDependency(qbus);

        app.synth();
    }
}