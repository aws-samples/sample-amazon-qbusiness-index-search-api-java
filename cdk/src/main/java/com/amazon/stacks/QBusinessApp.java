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
        new TokenVendingMachineStack(app, "TokenVendingMachineStack");
        
        // Create the QBusiness stack (which imports values from TokenVendingMachine)
        new QBusinessStack(app, "QBusinessStack");
        
        // Create the Search stack last
        // Note: This stack depends on both the QBusiness and TokenVendingMachine stacks
        new SearchStack(app, "SearchStack");
        
        app.synth();
    }
}