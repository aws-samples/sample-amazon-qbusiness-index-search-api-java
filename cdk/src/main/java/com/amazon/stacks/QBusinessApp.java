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

        // 1) TVM API → outputs TvmIssuerUrl & TvmAudience
        TokenVendingMachineStack tvm = new TokenVendingMachineStack(app, "TokenVendingMachineStack");
        
        // 2) Build OIDC provider from those exports
        OidcProviderStack oidc = new OidcProviderStack(app, "OidcProviderStack");
        oidc.addDependency(tvm);
        
        // 3) QBusiness consumes that provider
        QBusinessStack qbus = new QBusinessStack(app, "QBusinessStack", oidc.getProvider());
        qbus.addDependency(oidc);
        
        // 4) Search consumes both TVM and QBusiness outputs
        SearchStack search = new SearchStack(app, "SearchStack",
                oidc.getProvider(),
                tvm.getApi().getUrl(),
                tvm.getAudience(),
                qbus.getApplicationId(),
                qbus.getRetrieverId());
        search.addDependency(qbus);
        
        app.synth();
    }
}