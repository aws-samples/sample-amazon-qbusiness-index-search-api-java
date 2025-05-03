package com.amazon.stacks;

import com.amazon.policies.SearchWithTipRolePolicy;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Fn;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.constructs.Construct;

import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.iam.ManagedPolicy;

import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Code;

import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.CorsOptions;
import software.amazon.awscdk.services.apigateway.Cors;

import software.amazon.awscdk.Duration;

import java.util.List;
import java.util.Map;

public class SearchStack extends Stack {
    public SearchStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public SearchStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        //
        // 1) Import the TVM & QBusiness outputs you exported earlier
        //
        String tvmOidcProviderArn = Fn.importValue("TvmOidcProviderArn");
        String tvmIssuerUrl       = Fn.importValue("TvmIssuerUrl");
        String tvmAudience        = Fn.importValue("TvmAudience");

        String tvmApiUrl     = Fn.importValue("TvmApiUrl");
        String tokenEndpoint = tvmApiUrl.endsWith("/")
                ? tvmApiUrl + "token"
                : tvmApiUrl + "/token";

        String applicationId = Fn.importValue("QBusApplicationId");
        String retrieverId   = Fn.importValue("QBusRetrieverId");

        //
        // 2) Create the STS‐assumable role with your principal tag + SearchRelevantContent policy
        //
        Role searchWithTipRole = SearchWithTipRolePolicy.create(
                this,
                "SearchWithTipRole",
                tvmOidcProviderArn,
                tvmIssuerUrl,
                tvmAudience,
                applicationId
        );
        String roleArn = searchWithTipRole.getRoleArn();

        //
        // 3) Create a plain Lambda execution role for the handler itself
        //
        Role lambdaExecRole = Role.Builder.create(this, "SearchLambdaExecRole")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole")
                ))
                .build();

        //
        // 4) Define your SearchHandler function
        //
        Function searchFn = Function.Builder.create(this, "SearchHandler")
                .functionName("SearchHandler")
                .runtime(Runtime.JAVA_21)
                .handler("com.amazon.SearchHandler::handleRequest")
                .code(Code.fromAsset("services/search/target/search-1.0-SNAPSHOT.jar"))
                .role(lambdaExecRole)
                .timeout(Duration.seconds(30))
                .environment(Map.of(
                        "TOKEN_ENDPOINT",    tokenEndpoint,
                        "ROLE_ARN",          roleArn,
                        "QBUS_APP_ID",       applicationId,
                        "QBUS_RETRIEVER_ID", retrieverId
                ))
                .build();

        // Allow API Gateway to invoke our Lambda
        searchFn.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));

        //
        // 5) Expose it via API Gateway
        //
        RestApi api = RestApi.Builder.create(this, "SearchApi")
                .restApiName("SearchRelevantContentApi")
                .defaultCorsPreflightOptions(CorsOptions.builder()
                        .allowOrigins(Cors.ALL_ORIGINS)
                        .allowMethods(Cors.ALL_METHODS)
                        .allowHeaders(List.of("Content-Type", "Authorization"))
                        .build())
                .build();

        api.getRoot()
                .addResource("search")
                .addMethod("POST", new LambdaIntegration(searchFn));

        //
        // 6) Export the URL so downstream stacks/users can pick it up
        //
        new CfnOutput(this, "SearchApiUrl", CfnOutputProps.builder()
                .exportName("SearchApiUrl")
                .value(api.getUrl() + "search")
                .build());
    }

    public static void main(final String[] args) {
        App app = new App();
        new SearchStack(app, "SearchStack");
        app.synth();
    }
}