package com.amazon.stacks;

import com.amazon.policies.JwksEndpointHandlerPolicy;
import com.amazon.policies.OpenIDConfigurationHandlerPolicy;
import com.amazon.policies.TokenVendingMachineHandlerPolicy;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;

import software.amazon.awscdk.services.iam.OpenIdConnectProvider;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;

import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.kms.KeySpec;
import software.amazon.awscdk.services.kms.KeyUsage;
import software.amazon.awscdk.services.kms.Alias;

import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Code;

import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.iam.ManagedPolicy;

import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.CorsOptions;
import software.amazon.awscdk.services.apigateway.Cors;

import java.util.List;
import java.util.Map;

public class TokenVendingMachineStack extends Stack {
    public TokenVendingMachineStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public TokenVendingMachineStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // 1) KMS asymmetric key for signing
        Key signingKey = Key.Builder.create(this, "TvmSigningKey")
                .keySpec(KeySpec.RSA_2048)
                .keyUsage(KeyUsage.SIGN_VERIFY)
                .build();

        Alias.Builder.create(this, "TvmSigningKeyAlias")
                .aliasName("alias/tvm-token-signing")
                .targetKey(signingKey)
                .build();

        // 2) Lambda execution role (basic + inline policies + KMS Sign)
        Role lambdaRole = Role.Builder.create(this, "TvmLambdaExecRole")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole")
                ))
                .inlinePolicies(Map.of(
                        "OpenIDConfigurationHandlerPolicy",  OpenIDConfigurationHandlerPolicy.create(this),
                        "JwksEndpointHandlerPolicy",         JwksEndpointHandlerPolicy.create(this),
                        "TokenVendingMachineHandlerPolicy",  TokenVendingMachineHandlerPolicy.create(this)
                ))
                .build();

        // grant the role permission to call KMS:Sign on our key
        signingKey.grant(lambdaRole, "kms:Sign");

        // 3) API Gateway
        RestApi api = RestApi.Builder.create(this, "TvmApi")
                .restApiName("TokenVendingMachineApi")
                .defaultCorsPreflightOptions(CorsOptions.builder()
                        .allowOrigins(Cors.ALL_ORIGINS)
                        .allowMethods(Cors.ALL_METHODS)
                        .allowHeaders(List.of("Content-Type", "Authorization"))
                        .build())
                .build();

        String issuerUrl = api.getUrl(); // e.g. https://xyz.execute-api.us-east-1.amazonaws.com/prod/

        // 4) TVM JAR asset
        String tvmJar = "services/tvm/target/tvm-1.0-SNAPSHOT.jar";

        // 5) Lambda functions (named after their handler classes)
        Function openIdConfigurationHandler = Function.Builder.create(this, "OpenIdConfigurationHandler")
                .functionName("OpenIdConfigurationHandler")
                .runtime(Runtime.JAVA_21)
                .handler("com.amazon.OpenIdConfigurationHandler::handleRequest")
                .code(Code.fromAsset(tvmJar))
                .role(lambdaRole)
                .environment(Map.of("ISSUER_URL", issuerUrl))
                .timeout(Duration.seconds(10))
                .build();

        Function jwksEndpointHandler = Function.Builder.create(this, "JwksEndpointHandler")
                .functionName("JwksEndpointHandler")
                .runtime(Runtime.JAVA_21)
                .handler("com.amazon.JwksEndpointHandler::handleRequest")
                .code(Code.fromAsset(tvmJar))
                .role(lambdaRole)
                .environment(Map.of("KEY_ID", signingKey.getKeyId()))
                .timeout(Duration.seconds(10))
                .build();

        Function tokenVendingMachineHandler = Function.Builder.create(this, "TokenVendingMachineHandler")
                .functionName("TokenVendingMachineHandler")
                .runtime(Runtime.JAVA_21)
                .handler("com.amazon.TokenVendingMachineHandler::handleRequest")
                .code(Code.fromAsset(tvmJar))
                .role(lambdaRole)
                .environment(Map.of(
                        "ISSUER_URL", issuerUrl,
                        "AUDIENCE",   "qbusiness-audience",
                        "KEY_ID",     signingKey.getKeyId()
                ))
                .timeout(Duration.seconds(10))
                .build();

        // 6) API Gateway integrations
        var wellKnown = api.getRoot().addResource(".well-known");

        wellKnown.addResource("openid-configuration")
                .addMethod("GET", new LambdaIntegration(openIdConfigurationHandler));

        wellKnown.addResource("jwks.json")
                .addMethod("GET", new LambdaIntegration(jwksEndpointHandler));

        api.getRoot()
                .addResource("token")
                .addMethod("POST", new LambdaIntegration(tokenVendingMachineHandler));

        // 7) Grant API Gateway permission to invoke each Lambda
        openIdConfigurationHandler.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));
        jwksEndpointHandler.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));
        tokenVendingMachineHandler.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));

        // strip trailing slash if present
        String issuer = issuerUrl.replaceAll("/+$", "");

        // thumbprint of the TVM’s TLS cert
        List<String> thumbprints = List.of("9e99a48a9960b14926bb7f3b02e22da0afd8f4f");

        OpenIdConnectProvider oidcProvider = OpenIdConnectProvider.Builder.create(this, "TvmIamOidcProvider")
                .url(issuer)                             // e.g. https://abc123.execute-api.us-east-1.amazonaws.com/prod
                .clientIds(List.of("qbusiness-audience"))// must match the “aud” in your TVM tokens
                .thumbprints(thumbprints)
                .build();

        // export its ARN & audience for downstream stacks
        new CfnOutput(this, "TvmOidcProviderArn", CfnOutputProps.builder()
                .value(oidcProvider.getOpenIdConnectProviderArn())
                .description("IAM OIDC Provider ARN for the TVM")
                .build());

        new CfnOutput(this, "TvmOidcAudience", CfnOutputProps.builder()
                .value("qbusiness-audience")
                .description("OIDC client ID (audience) for QBusiness")
                .build());

        new CfnOutput(this, "TvmOidcProviderArn", CfnOutputProps.builder()
                .exportName("TvmOidcProviderArn")
                .value(oidcProvider.getOpenIdConnectProviderArn())
                .build());
    }

    public static void main(final String[] args) {
        App app = new App();
        new TokenVendingMachineStack(app, "TokenVendingMachineStack");
        app.synth();
    }
}