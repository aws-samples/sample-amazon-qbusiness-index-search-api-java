package com.amazon.stacks;

import java.util.List;
import java.util.Map;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;
import software.amazon.awscdk.CfnResource;
import software.amazon.awscdk.services.iam.OpenIdConnectProvider;

public class QBusinessStack extends Stack {
    private final CfnResource application;
    private final CfnResource index;
    private final CfnResource retriever;

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
                    "ClientIdsForOIDC", List.of("qbusiness-audience"),
                    "AutoSubscriptionConfiguration", Map.of(
                        "AutoSubscribe",            "ENABLED",
                        "DefaultSubscriptionType",  "Q_BUSINESS"
                        )
                ))
                .build();

        // (2) Create the Index against that app
        this.index = CfnResource.Builder.create(this, "QBusinessIndex")
                .type("AWS::QBusiness::Index")
                .properties(Map.of(
                    "ApplicationId", this.application.getAtt("ApplicationId").toString(),
                    "DisplayName", "MyIndex"
                ))
                .build();
        
        // (3) Create a Retriever connected to the Index
        this.retriever = CfnResource.Builder.create(this, "QBusinessRetriever")
                .type("AWS::QBusiness::Retriever")
                .properties(Map.of(
                    "ApplicationId", this.application.getAtt("ApplicationId").toString(),
                    "DisplayName", "MyRetriever",
                    "Type", "NATIVE_INDEX",
                    "Configuration", Map.of(
                        "NativeIndexConfiguration", Map.of(
                            "IndexId", this.index.getAtt("IndexId").toString()
                        )
                    )
                ))
                .build();

        // Add dependency to ensure index is created before retriever
        this.retriever.addDependency(this.index);

        // (4) Export the Application ID
        new CfnOutput(this, "QBusinessApplicationId", CfnOutputProps.builder()
                .exportName("QBusinessApplicationId")
                .value(this.application.getAtt("ApplicationId").toString())
                .description("The Amazon Q Business Application ID")
                .build());

        // (5) Export the Index ID
        new CfnOutput(this, "QBusinessIndexId", CfnOutputProps.builder()
                .exportName("QBusinessIndexId")
                .value(this.index.getAtt("IndexId").toString())
                .description("The Amazon Q Business Index ID")
                .build());
                
        // (6) Export the Retriever ID
        new CfnOutput(this, "QBusinessRetrieverId", CfnOutputProps.builder()
                .exportName("QBusinessRetrieverId")
                .value(this.retriever.getAtt("RetrieverId").toString())
                .description("The Amazon Q Business Retriever ID")
                .build());
    }

    // Getter methods for cross-stack references
    public String getApplicationId() {
        return this.application.getAtt("ApplicationId").toString();
    }

    public String getIndexId() {
        return this.index.getAtt("IndexId").toString();
    }
    
    public String getRetrieverId() {
        return this.retriever.getAtt("RetrieverId").toString();
    }
}