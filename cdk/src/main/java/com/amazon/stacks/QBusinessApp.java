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

        // Create the Token Vending Machine stack first (since others depend on it)
        TokenVendingMachineStack tvmStack = new TokenVendingMachineStack(app, "TokenVendingMachineStack");
        
        // Create the QBusiness stack with a reference to the OIDC provider from the TVM stack
        QBusinessStack qbusStack = new QBusinessStack(app, "QBusinessStack", tvmStack.getOidcProvider());
        
        // Create the Search stack last with references from both upstream stacks
        SearchStack searchStack = new SearchStack(app, "SearchStack", 
                tvmStack.getOidcProvider(),
                tvmStack.getApi().getUrl(),
                tvmStack.getAudience(),
                qbusStack.getApplicationId(),
                qbusStack.getRetrieverId());
        
        app.synth();
    }
}