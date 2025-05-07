package com.amazon.stacks;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Aspects;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Fn;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;
import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import io.github.cdklabs.cdknag.NagSuppressions;
import io.github.cdklabs.cdknag.NagPackSuppression;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.CorsOptions;
import software.amazon.awscdk.services.apigateway.Cors;
import software.amazon.awscdk.services.apigateway.RequestValidator;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.MethodLoggingLevel;
import software.amazon.awscdk.services.apigateway.AccessLogFormat;
import software.amazon.awscdk.services.apigateway.StageOptions;
import software.amazon.awscdk.services.apigateway.LogGroupLogDestination;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.wafv2.CfnWebACL;
import software.amazon.awscdk.services.wafv2.CfnWebACLAssociation;

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

import java.util.List;
import java.util.Map;

public class TokenVendingMachineStack extends Stack {
    private final RestApi api;
    private final String audience = "qbusiness-audience";

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
        NagSuppressions.addResourceSuppressions(signingKey, List.of(
                NagPackSuppression.builder()
                        .id("AwsSolutions-KMS5")
                        .reason("OIDC signing requires an asymmetric key (RSA); KMS asymmetric keys cannot auto-rotate.")
                        .build()
        ));
        Alias.Builder.create(this, "TvmSigningKeyAlias")
                .aliasName("alias/tvm-token-signing")
                .targetKey(signingKey)
                .build();

        // 2) Lambda role with AWS-managed log policy
        Role lambdaRole = Role.Builder.create(this, "TvmLambdaExecRole")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole")
                ))
                .build();
        NagSuppressions.addResourceSuppressions(
                lambdaRole,
                List.of(
                        NagPackSuppression.builder()
                                .id("AwsSolutions-IAM4")
                                .reason("AWSLambdaBasicExecutionRole is required for Lambda logging")
                                .build()
                ), true
        );
        signingKey.grant(lambdaRole, "kms:Sign", "kms:GetPublicKey", "kms:DescribeKey");

        // 3) API Gateway access logs
        LogGroup apiAccessLogs = LogGroup.Builder.create(this, "TvmApiAccessLogs")
                .logGroupName("/aws/apigateway/TokenVendingMachineApi-access-logs")
                .retention(RetentionDays.ONE_MONTH)
                .build();

        // 4) WAF Web ACL
        CfnWebACL wafAcl = CfnWebACL.Builder.create(this, "TvmApiWafAcl")
                .name("TvmApiWafAcl")
                .scope("REGIONAL")
                .defaultAction(CfnWebACL.DefaultActionProperty.builder()
                        .allow(CfnWebACL.AllowActionProperty.builder().build())
                        .build())
                .visibilityConfig(CfnWebACL.VisibilityConfigProperty.builder()
                        .cloudWatchMetricsEnabled(true)
                        .metricName("TvmApiWafAcl")
                        .sampledRequestsEnabled(true)
                        .build())
                .rules(List.of(
                        CfnWebACL.RuleProperty.builder()
                                .name("RateLimitRule")
                                .priority(1)
                                .action(CfnWebACL.RuleActionProperty.builder()
                                        .block(CfnWebACL.BlockActionProperty.builder().build())
                                        .build())
                                .statement(CfnWebACL.StatementProperty.builder()
                                        .rateBasedStatement(CfnWebACL.RateBasedStatementProperty.builder()
                                                .limit(1000)
                                                .aggregateKeyType("IP")
                                                .build())
                                        .build())
                                .visibilityConfig(CfnWebACL.VisibilityConfigProperty.builder()
                                        .cloudWatchMetricsEnabled(true)
                                        .metricName("RateLimitRule")
                                        .sampledRequestsEnabled(true)
                                        .build())
                                .build()
                ))
                .build();

        // 5) REST API
        this.api = RestApi.Builder.create(this, "TvmApi")
                .restApiName("TokenVendingMachineApi")
                .deployOptions(StageOptions.builder()
                        .stageName("prod")
                        .accessLogDestination(new LogGroupLogDestination(apiAccessLogs))
                        .accessLogFormat(AccessLogFormat.clf())
                        .loggingLevel(MethodLoggingLevel.INFO)
                        .dataTraceEnabled(true)
                        .build())
                .defaultCorsPreflightOptions(CorsOptions.builder()
                        .allowOrigins(Cors.ALL_ORIGINS)
                        .allowMethods(Cors.ALL_METHODS)
                        .allowHeaders(List.of("Content-Type", "Authorization"))
                        .build())
                .build();
        NagSuppressions.addResourceSuppressions(
                api,
                List.of(
                        NagPackSuppression.builder()
                                .id("AwsSolutions-IAM4")
                                .reason("API Gateway needs AWS-managed log policy for CloudWatch pushes")
                                .build()
                ), true
        );

        // 6) Associate WAF via L1 resource after API/Stage exists
        CfnWebACLAssociation.Builder.create(this, "TvmApiWafAssociation")
                .resourceArn(Fn.sub(
                        "arn:aws:apigateway:${AWS::Region}::/restapis/${RestApiId}/stages/prod",
                        Map.of("RestApiId", api.getRestApiId())))
                .webAclArn(wafAcl.getAttrArn())
                .build();

        // 7) Request Validator
        RequestValidator validator = RequestValidator.Builder.create(this, "TvmApiValidator")
                .restApi(api)
                .requestValidatorName("body-and-params-validator")
                .validateRequestBody(true)
                .validateRequestParameters(true)
                .build();
        api.getDeploymentStage().applyRemovalPolicy(RemovalPolicy.RETAIN);

        // 8) Lambda handlers
        String tvmJar = "services/TokenVendingMachine/target/tvm-1.0.0-SNAPSHOT.jar";
        Function openIdConfigurationHandler = Function.Builder.create(this, "OpenIdConfigurationHandler")
                .runtime(Runtime.JAVA_21)
                .handler("com.amazon.OpenIdConfigurationHandler::handleRequest")
                .code(Code.fromAsset(tvmJar))
                .role(lambdaRole)
                .timeout(Duration.seconds(10))
                .build();
        Function jwksEndpointHandler = Function.Builder.create(this, "JwksEndpointHandler")
                .runtime(Runtime.JAVA_21)
                .handler("com.amazon.JwksEndpointHandler::handleRequest")
                .code(Code.fromAsset(tvmJar))
                .role(lambdaRole)
                .environment(Map.of("KEY_ID", signingKey.getKeyId()))
                .timeout(Duration.seconds(10))
                .build();
        Function tokenVendingMachineHandler = Function.Builder.create(this, "TokenVendingMachineHandler")
                .runtime(Runtime.JAVA_21)
                .handler("com.amazon.TokenVendingMachineHandler::handleRequest")
                .code(Code.fromAsset(tvmJar))
                .role(lambdaRole)
                .environment(Map.of(
                        "AUDIENCE", audience,
                        "KEY_ID", signingKey.getKeyId()
                ))
                .timeout(Duration.seconds(10))
                .build();

        // 9) API integrations
        var wellKnown = api.getRoot().addResource(".well-known");
        var openidConfigMethod = wellKnown.addResource("openid-configuration").addMethod(
                "GET", new LambdaIntegration(openIdConfigurationHandler),
                MethodOptions.builder().requestValidator(validator).build());
        var jwksMethod = wellKnown.addResource("jwks.json").addMethod(
                "GET", new LambdaIntegration(jwksEndpointHandler),
                MethodOptions.builder().requestValidator(validator).build());
        var tokenMethod = api.getRoot().addResource("token").addMethod(
                "POST", new LambdaIntegration(tokenVendingMachineHandler),
                MethodOptions.builder().requestValidator(validator).build());

        // suppress public endpoints
        NagSuppressions.addResourceSuppressions(openidConfigMethod, List.of(
                NagPackSuppression.builder().id("AwsSolutions-APIG4").reason("OIDC Discovery endpoint must be public").build(),
                NagPackSuppression.builder().id("AwsSolutions-COG4").reason("No Cognito auth on discovery").build()
        ));
        NagSuppressions.addResourceSuppressions(jwksMethod, List.of(
                NagPackSuppression.builder().id("AwsSolutions-APIG4").reason("JWKS endpoint must be public").build(),
                NagPackSuppression.builder().id("AwsSolutions-COG4").reason("No Cognito auth on JWKS").build()
        ));
        NagSuppressions.addResourceSuppressions(tokenMethod, List.of(
                NagPackSuppression.builder().id("AwsSolutions-APIG4").reason("Token endpoint must be public for OAuth2 clients").build(),
                NagPackSuppression.builder().id("AwsSolutions-COG4").reason("No Cognito auth on token").build()
        ));

        // 10) Invoke permissions
        openIdConfigurationHandler.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));
        jwksEndpointHandler.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));
        tokenVendingMachineHandler.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));

        // 11) Exports
        String issuer = api.getUrl().replaceAll("/+$", "");
        new CfnOutput(this, "TvmIssuerUrl", CfnOutputProps.builder()
                .value(issuer)
                .description("OIDC Issuer URL for QBusiness")
                .exportName("TvmIssuerUrl")
                .build());
        new CfnOutput(this, "TvmAudience", CfnOutputProps.builder()
                .value(audience)
                .description("OIDC client ID (audience) for QBusiness")
                .exportName("TvmAudience")
                .build());
        new CfnOutput(this, "TvmApiUrl", CfnOutputProps.builder()
                .value(api.getUrl())
                .description("API Gateway URL for the TVM")
                .exportName("TvmApiUrl")
                .build());
    }

    public RestApi getApi() { return api; }
    public String getAudience() { return audience; }

    public static void main(final String[] args) {
        App app = new App();
        Aspects.of(app).add(new AwsSolutionsChecks());
        new TokenVendingMachineStack(app, "TokenVendingMachineStack");
        app.synth();
    }
}
