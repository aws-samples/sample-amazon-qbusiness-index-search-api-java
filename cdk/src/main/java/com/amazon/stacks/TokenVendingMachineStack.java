package com.amazon.stacks;


import software.amazon.awscdk.App;
import software.amazon.awscdk.Aspects;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;
import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import io.github.cdklabs.cdknag.NagSuppressions;
import io.github.cdklabs.cdknag.NagPackSuppression;

import software.amazon.awscdk.services.apigateway.RestApi;
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
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.Effect;

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

import java.util.List;
import java.util.Map;

public class TokenVendingMachineStack extends Stack {
    // Private fields for L2 cross-stack references
    private final RestApi api;
    private final String audience = "qbusiness-audience";
    
    public TokenVendingMachineStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public TokenVendingMachineStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // 1) KMS asymmetric key for signing
        // For OIDC, we need an asymmetric key (RSA) because:
        // - Clients can verify JWT signatures using the public key from JWKS endpoint
        // - The private key remains secure in KMS
        // - No shared secrets are required between the TVM and clients
        // Note: KMS doesn't support automatic rotation for asymmetric keys
        Key signingKey = Key.Builder.create(this, "TvmSigningKey")
                .keySpec(KeySpec.RSA_2048)
                .keyUsage(KeyUsage.SIGN_VERIFY)
                .build();
                
        // Suppress the key rotation finding since asymmetric keys cannot be automatically rotated
        NagSuppressions.addResourceSuppressions(signingKey, List.of(
            NagPackSuppression.builder()
                .id("AwsSolutions-KMS5")
                .reason("OIDC signing requires an asymmetric key (RSA) for secure token verification; KMS does not support automatic rotation for asymmetric keys.")
                .build()
        ));

        Alias.Builder.create(this, "TvmSigningKeyAlias")
                .aliasName("alias/tvm-token-signing")
                .targetKey(signingKey)
                .build();

        // 2) Create a basic Lambda execution role with just the Lambda service principal
        Role lambdaRole = Role.Builder.create(this, "TvmLambdaExecRole")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .build();

        // Grant KMS permissions using the key.grant() method instead of wildcard policies
        // This properly scopes permissions to the specific key
        signingKey.grant(lambdaRole, "kms:Sign", "kms:GetPublicKey", "kms:DescribeKey");

        // 3) API Gateway with request validation, logging, and WAF
        // Create a log group for access logs
        LogGroup apiAccessLogs = LogGroup.Builder.create(this, "TvmApiAccessLogs")
                .logGroupName("/aws/apigateway/TokenVendingMachineApi-access-logs")
                .retention(RetentionDays.ONE_MONTH)
                .build();
        
        // Create the REST API first with logging options
        this.api = RestApi.Builder.create(this, "TvmApi")
                .restApiName("TokenVendingMachineApi")
                // Auto-deployment options with logging
                .deployOptions(StageOptions.builder()
                        .stageName("prod")
                        .accessLogDestination(new LogGroupLogDestination(apiAccessLogs))
                        .accessLogFormat(AccessLogFormat.clf())
                        .loggingLevel(MethodLoggingLevel.INFO)
                        .dataTraceEnabled(true)
                        .build())
                // CORS options
                .defaultCorsPreflightOptions(CorsOptions.builder()
                        .allowOrigins(Cors.ALL_ORIGINS)
                        .allowMethods(Cors.ALL_METHODS)
                        .allowHeaders(List.of("Content-Type", "Authorization"))
                        .build())
                .build();
                
        // Suppress the managed policy warning for the CloudWatch role
        // API Gateway requires this managed policy for pushing logs to CloudWatch
        NagSuppressions.addResourceSuppressions(
            api, 
            List.of(
                NagPackSuppression.builder()
                    .id("AwsSolutions-IAM4")
                    .reason("API Gateway requires the AWS-managed CloudWatch log push policy")
                    .build()
            ),
            true  // Apply to child resources
        );
                
        // Now create a request validator that's bound to the API
        RequestValidator validator = RequestValidator.Builder.create(this, "TvmApiValidator")
                .restApi(api)
                .requestValidatorName("body-and-params-validator")
                .validateRequestBody(true)
                .validateRequestParameters(true)
                .build();
                
        // Apply removal policy to deployment stage
        api.getDeploymentStage().applyRemovalPolicy(RemovalPolicy.RETAIN);
                
        // Create a WAF Web ACL to protect the API
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
                    // Add a rate-limiting rule
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
                
        // Associate the WAF WebACL with the API Gateway stage
        String stageName = "prod"; // We specified this explicitly in deployOptions
        CfnWebACLAssociation.Builder.create(this, "TvmApiWafAssociation")
                .resourceArn("arn:aws:apigateway:" + Stack.of(this).getRegion() + 
                            "::/restapis/" + this.api.getRestApiId() + 
                            "/stages/" + stageName)
                .webAclArn(wafAcl.getAttrArn())
                .build();
                
        // Add suppression for the WAF association (APIG3)
        NagSuppressions.addResourceSuppressions(
            this.api.getDeploymentStage(), 
            List.of(
                NagPackSuppression.builder()
                    .id("AwsSolutions-APIG3")
                    .reason("WAF is associated via CfnWebACLAssociation, but CDK cannot detect this in the L2 construct")
                    .build()
            )
        );

        String issuerUrl = this.api.getUrl(); // e.g. https://xyz.execute-api.us-east-1.amazonaws.com/prod/

        // 4) TVM JAR asset - path adjusted to match the actual build location
        // This path must be relative to the project root directory
        String tvmJar = "services/TokenVendingMachine/target/tvm-1.0.0-SNAPSHOT.jar";

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
                
        // Create CloudWatch log groups using the actual function names
        LogGroup openIdConfigLogGroup = LogGroup.Builder.create(this, "OpenIdConfigurationLogs")
                .logGroupName("/aws/lambda/" + openIdConfigurationHandler.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .build();
                
        LogGroup jwksEndpointLogGroup = LogGroup.Builder.create(this, "JwksEndpointLogs")
                .logGroupName("/aws/lambda/" + jwksEndpointHandler.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .build();
                
        LogGroup tokenVendingMachineLogGroup = LogGroup.Builder.create(this, "TokenVendingMachineLogs")
                .logGroupName("/aws/lambda/" + tokenVendingMachineHandler.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .build();
                
        // Create CloudWatch logs policy with specific log group ARNs (least privilege)
        PolicyStatement lambdaLogsPolicy = PolicyStatement.Builder.create()
                .sid("AllowLambdaLogs")
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "logs:CreateLogStream", 
                        "logs:PutLogEvents"
                ))
                .resources(List.of(
                        openIdConfigLogGroup.getLogGroupArn() + ":*",
                        jwksEndpointLogGroup.getLogGroupArn() + ":*",
                        tokenVendingMachineLogGroup.getLogGroupArn() + ":*"
                )) // Scoped to specific Lambda log groups
                .build();
                
        // Create policy document for CloudWatch logging
        PolicyDocument cloudWatchLogsPolicy = PolicyDocument.Builder.create()
                .statements(List.of(lambdaLogsPolicy))
                .build();
                
        // Add the CloudWatch logs policy to the Lambda role
        lambdaRole.addToPolicy(lambdaLogsPolicy);
        
        // Add suppressions for the ':*' suffix on log group ARNs
        NagSuppressions.addResourceSuppressions(
            lambdaRole, 
            List.of(
                NagPackSuppression.builder()
                    .id("AwsSolutions-IAM5")
                    .reason("LogGroup ARN needs a ':*' suffix to allow writing to all streams in that specific group.")
                    .build()
            ),
            true  // Apply to child nodes
        );

        // 6) API Gateway integrations
        var wellKnown = api.getRoot().addResource(".well-known");

        // Add the OIDC discovery endpoints and store references for suppressions
        var openidConfigResource = wellKnown.addResource("openid-configuration");
        var openidConfigMethod = openidConfigResource.addMethod("GET", 
                new LambdaIntegration(openIdConfigurationHandler),
                MethodOptions.builder()
                        .requestValidator(validator)
                        .build());
        
        var jwksResource = wellKnown.addResource("jwks.json");
        var jwksMethod = jwksResource.addMethod("GET", 
                new LambdaIntegration(jwksEndpointHandler),
                MethodOptions.builder()
                        .requestValidator(validator)
                        .build());
        
        var tokenResource = api.getRoot().addResource("token");
        var tokenMethod = tokenResource.addMethod("POST", 
                new LambdaIntegration(tokenVendingMachineHandler),
                MethodOptions.builder()
                        .requestValidator(validator)
                        .build());
        
        // Add suppressions for the OIDC endpoints since they must be public
        NagSuppressions.addResourceSuppressions(
            openidConfigMethod,
            List.of(
                NagPackSuppression.builder()
                    .id("AwsSolutions-APIG4")
                    .reason("OIDC Discovery requires this endpoint to be public")
                    .build(),
                NagPackSuppression.builder()
                    .id("AwsSolutions-COG4")
                    .reason("OIDC Discovery endpoints can't use Cognito authorization")
                    .build()
            )
        );
        
        NagSuppressions.addResourceSuppressions(
            jwksMethod,
            List.of(
                NagPackSuppression.builder()
                    .id("AwsSolutions-APIG4")
                    .reason("OIDC JWKS requires this endpoint to be public")
                    .build(),
                NagPackSuppression.builder()
                    .id("AwsSolutions-COG4")
                    .reason("OIDC JWKS endpoints can't use Cognito authorization")
                    .build()
            )
        );
        
        NagSuppressions.addResourceSuppressions(
            tokenMethod,
            List.of(
                NagPackSuppression.builder()
                    .id("AwsSolutions-APIG4")
                    .reason("Token endpoint must be accessible with OAuth2 client authentication, not API Gateway auth")
                    .build(),
                NagPackSuppression.builder()
                    .id("AwsSolutions-COG4")
                    .reason("Token endpoint uses OAuth2 client authentication, not Cognito")
                    .build()
            )
        );

        // 7) Grant API Gateway permission to invoke each Lambda
        openIdConfigurationHandler.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));
        jwksEndpointHandler.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));
        tokenVendingMachineHandler.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));

        // strip trailing slash if present
        String issuer = issuerUrl.replaceAll("/+$", "");
        
        // Export audience for downstream stacks
        new CfnOutput(this, "TvmOidcAudience", CfnOutputProps.builder()
                .value(this.audience)
                .description("OIDC client ID (audience) for QBusiness")
                .exportName("TvmAudience")
                .build());
                
        new CfnOutput(this, "TvmIssuerUrl", CfnOutputProps.builder()
                .value(issuer)
                .description("OIDC Issuer URL for QBusiness")
                .exportName("TvmIssuerUrl")
                .build());
                
        new CfnOutput(this, "TvmApiUrl", CfnOutputProps.builder()
                .value(api.getUrl())
                .description("API Gateway URL for the TVM")
                .exportName("TvmApiUrl")
                .build());
    }

    // Getter methods for cross-stack references
    public RestApi getApi() {
        return this.api;
    }
    
    public String getAudience() {
        return this.audience;
    }
    
    public static void main(final String[] args) {
        App app = new App();
        
        // Apply AWS Solutions security checks to all constructs in the app
        Aspects.of(app).add(new AwsSolutionsChecks());
        
        new TokenVendingMachineStack(app, "TokenVendingMachineStack");
        app.synth();
    }
}