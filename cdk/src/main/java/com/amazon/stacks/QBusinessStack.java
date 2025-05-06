package com.amazon.stacks;

import java.util.List;
import java.util.Map;

import software.amazon.awscdk.Fn;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Aspects;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;
import software.amazon.awscdk.CfnResource;
import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import io.github.cdklabs.cdknag.NagSuppressions;

public class QBusinessStack extends Stack {
    public QBusinessStack(final Construct scope, final String id) {
        this(scope, id, StackProps.builder().build());
    }

    public QBusinessStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // (1) Create the Q Business application using generic CfnResource
        String oidcArn = Fn.importValue("TvmOidcProviderArn");
        
        CfnResource app = CfnResource.Builder.create(this, "QBusinessApp")
                .type("AWS::QBusiness::Application")
                .properties(Map.of(
                    "DisplayName", "MyApp",
                    "IdentityType", "AWS_IAM_IDP_OIDC", 
                    "IamIdentityProviderArn", oidcArn,
                    "ClientIdsForOidc", List.of("qbusiness-audience")
                ))
                .build();
                
        // (2) Create the Retriever (Index) against that app
        CfnResource index = CfnResource.Builder.create(this, "QBusinessIndex")
                .type("AWS::QBusiness::Index")
                .properties(Map.of(
                    "ApplicationId", app.getAtt("ApplicationId").toString(),
                    "DisplayName", "MyIndex"
                ))
                .build();

        // (3) Export the Application ID
        new CfnOutput(this, "QBusinessApplicationId", CfnOutputProps.builder()
                .exportName("QBusinessApplicationId")
                .value(app.getAtt("ApplicationId").toString())
                .description("The Amazon Q Business Application ID")
                .build());

        // (4) Export the Retriever (Index) ID
        new CfnOutput(this, "QBusinessRetrieverId", CfnOutputProps.builder()
                .exportName("QBusinessRetrieverId")
                .value(index.getAtt("IndexId").toString())
                .description("The Amazon Q Business Retriever (Index) ID")
                .build());
    }

    public static void main(final String[] args) {
        App app = new App();
        
        // Apply AWS Solutions security checks to all constructs in the app
        Aspects.of(app).add(new AwsSolutionsChecks());
        
        // Create the stack
        new QBusinessStack(app, "QBusinessStack");
        
        app.synth();
    }
}