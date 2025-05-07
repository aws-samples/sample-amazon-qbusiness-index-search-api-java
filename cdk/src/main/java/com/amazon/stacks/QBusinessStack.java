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
import software.amazon.awscdk.services.iam.OpenIdConnectProvider;
import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import io.github.cdklabs.cdknag.NagSuppressions;

public class QBusinessStack extends Stack {
    private final CfnResource application;
    private final CfnResource index;
    
    public QBusinessStack(final Construct scope, final String id, final OpenIdConnectProvider oidcProvider) {
        this(scope, id, oidcProvider, StackProps.builder().build());
    }

    public QBusinessStack(final Construct scope, final String id, final OpenIdConnectProvider oidcProvider, final StackProps props) {
        super(scope, id, props);

        // (1) Create the Q Business application using generic CfnResource
        String oidcArn = oidcProvider.getOpenIdConnectProviderArn();
        
        this.application = CfnResource.Builder.create(this, "QBusinessApp")
                .type("AWS::QBusiness::Application")
                .properties(Map.of(
                    "DisplayName", "MyApp",
                    "IdentityType", "AWS_IAM_IDP_OIDC", 
                    "IamIdentityProviderArn", oidcArn,
                    "ClientIdsForOidc", List.of("qbusiness-audience")
                ))
                .build();
                
        // (2) Create the Retriever (Index) against that app
        this.index = CfnResource.Builder.create(this, "QBusinessIndex")
                .type("AWS::QBusiness::Index")
                .properties(Map.of(
                    "ApplicationId", this.application.getAtt("ApplicationId").toString(),
                    "DisplayName", "MyIndex"
                ))
                .build();

        // (3) Export the Application ID
        new CfnOutput(this, "QBusinessApplicationId", CfnOutputProps.builder()
                .exportName("QBusinessApplicationId")
                .value(this.application.getAtt("ApplicationId").toString())
                .description("The Amazon Q Business Application ID")
                .build());

        // (4) Export the Retriever (Index) ID
        new CfnOutput(this, "QBusinessRetrieverId", CfnOutputProps.builder()
                .exportName("QBusinessRetrieverId")
                .value(this.index.getAtt("IndexId").toString())
                .description("The Amazon Q Business Retriever (Index) ID")
                .build());
    }
    
    // Getter methods for cross-stack references
    public String getApplicationId() {
        return this.application.getAtt("ApplicationId").toString();
    }
    
    public String getRetrieverId() {
        return this.index.getAtt("IndexId").toString();
    }
}