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
import software.amazon.awscdk.services.apigateway.CfnStage;
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
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.secretsmanager.SecretStringGenerator;
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
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyDocument;
import com.amazon.policies.TokenVendingMachineHandlerPolicy;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;

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
                        .reason("OIDC signing requires an asymmetric RSA key for JWT verification. Per AWS documentation, asymmetric KMS keys do not support automatic rotation due to their mathematical properties. This is an accepted design limitation for OIDC providers.")
                        .build()
        ));

        // 2) Store just the keyId in Secrets Manager
        Secret keySecret = Secret.Builder.create(this, "TvmKeySecret")
                .generateSecretString(SecretStringGenerator.builder()
                        .secretStringTemplate("{\"keyId\":\"" + signingKey.getKeyId() + "\"}")
                        .generateStringKey("secret")
                        .build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
        NagSuppressions.addResourceSuppressions(keySecret,
                List.of(NagPackSuppression.builder()
                        .id("AwsSolutions-SMG4")
                        .reason("This secret only stores the KMS key ID for the signing key, not actual key material. The secret contains no sensitive cryptographic data requiring rotation as the KMS service handles all cryptographic operations securely.")
                        .build()), true
        );

        Alias.Builder.create(this, "TvmSigningKeyAlias")
                .aliasName("alias/tvm-token-signing")
                .targetKey(signingKey)
                .build();

        // 3) DynamoDB table for email allowlist
        Table emailAllowlistTable = Table.Builder.create(this, "TvmEmailAllowlist")
                .partitionKey(Attribute.builder()
                        .name("email")
                        .type(AttributeType.STRING)
                        .build())
                .tableName("TvmEmailAllowlist")
                .removalPolicy(RemovalPolicy.RETAIN)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build();

        NagSuppressions.addResourceSuppressions(emailAllowlistTable,
                List.of(NagPackSuppression.builder()
                        .id("AwsSolutions-DDB3")
                        .reason("Point-in-time recovery is not required for this allowlist table as it stores non-critical configuration data that can be recreated if needed. The table uses RETAIN removal policy to preserve data across deployments.")
                        .build()), true
        );

        // 4) Lambda execution role with proper policies
        PolicyDocument policyDocument = TokenVendingMachineHandlerPolicy.create(
                this, emailAllowlistTable.getTableArn());

        Role lambdaRole = Role.Builder.create(this, "TvmLambdaExecRole")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole")
                ))
                .inlinePolicies(Map.of(
                        "TvmHandlerPolicy", policyDocument
                ))
                .build();

        NagSuppressions.addResourceSuppressions(lambdaRole,
                List.of(
                        NagPackSuppression.builder()
                                .id("AwsSolutions-IAM4")
                                .reason("AWSLambdaBasicExecutionRole is required for Lambda logging and contains the minimal permissions needed for CloudWatch logs. This managed policy is an AWS best practice for Lambda functions.")
                                .build(),
                        NagPackSuppression.builder()
                                .id("AwsSolutions-IAM5")
                                .reason("The wildcard in the KMS policy is necessary to avoid circular dependencies when using key.grant() calls. The actual permissions are properly scoped at runtime to specific key ARNs through the DefaultPolicy and the principle of least privilege is maintained.")
                                .build()
                ), true
        );

        // Grant additional permissions
        signingKey.grant(lambdaRole, "kms:Sign", "kms:GetPublicKey", "kms:DescribeKey");
        keySecret.grantRead(lambdaRole);

        // 4) API Gateway access logs
        LogGroup apiAccessLogs = LogGroup.Builder.create(this, "TvmApiAccessLogs")
                .logGroupName("/aws/apigateway/TokenVendingMachineApi-access-logs")
                .retention(RetentionDays.ONE_MONTH)
                .build();

        // 5) WAF Web ACL
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

        // 6) Create REST API
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
        NagSuppressions.addResourceSuppressions(api,
                List.of(NagPackSuppression.builder()
                        .id("AwsSolutions-IAM4")
                        .reason("API GW needs AWS-managed log policy for CloudWatch")
                        .build()), true
        );

        // 7) Suppress cdk-nag on the L1 stage
        CfnStage cfnStage = (CfnStage) api.getDeploymentStage().getNode().getDefaultChild();
        NagSuppressions.addResourceSuppressions(cfnStage,
                List.of(NagPackSuppression.builder()
                        .id("AwsSolutions-APIG3")
                        .reason("The WAF protection is implemented but attached via CfnWebACLAssociation downstream which is not detected by this check. The WebACL implements rate limiting rules to protect against DoS attacks and excessive token requests.")
                        .build()), true
        );

        // 8) Associate WAF
        CfnWebACLAssociation.Builder.create(this, "TvmApiWafAssociation")
                .resourceArn(Fn.sub(
                        "arn:aws:apigateway:${AWS::Region}::/restapis/${RestApiId}/stages/prod",
                        Map.of("RestApiId", api.getRestApiId())))
                .webAclArn(wafAcl.getAttrArn())
                .build()
                .getNode().addDependency(cfnStage);

        // 9) RequestValidator
        RequestValidator validator = RequestValidator.Builder.create(this, "TvmApiValidator")
                .restApi(api)
                .requestValidatorName("body-and-params-validator")
                .validateRequestBody(true)
                .validateRequestParameters(true)
                .build();
        api.getDeploymentStage().applyRemovalPolicy(RemovalPolicy.RETAIN);

        // 10) Lambda handlers and integrations
        String tvmJar = "services/TokenVendingMachine/target/tvm-1.0.0-SNAPSHOT.jar";
        Function openIdConfigurationHandler = Function.Builder.create(this, "OpenIdConfigurationHandler")
                .runtime(Runtime.JAVA_21)
                .handler("com.amazon.OpenIdConfigurationHandler::handleRequest")
                .code(Code.fromAsset(tvmJar))
                .role(lambdaRole)
                .timeout(Duration.seconds(10))
                .memorySize(512)
                .build();
        Function jwksEndpointHandler = Function.Builder.create(this, "JwksEndpointHandler")
                .runtime(Runtime.JAVA_21)
                .handler("com.amazon.JwksEndpointHandler::handleRequest")
                .code(Code.fromAsset(tvmJar))
                .role(lambdaRole)
                .environment(Map.of(
                        "KEY_SECRET_NAME", keySecret.getSecretName()
                ))
                .timeout(Duration.seconds(10))
                .memorySize(512)
                .build();
        keySecret.grantRead(jwksEndpointHandler);
        Function tokenVendingMachineHandler = Function.Builder.create(this, "TokenVendingMachineHandler")
                .runtime(Runtime.JAVA_21)
                .handler("com.amazon.TokenVendingMachineHandler::handleRequest")
                .code(Code.fromAsset(tvmJar))
                .role(lambdaRole)
                .environment(Map.of(
                        "AUDIENCE", audience,
                        "KEY_SECRET_NAME", keySecret.getSecretName(),
                        "TABLE_NAME", emailAllowlistTable.getTableName()
                ))
                .timeout(Duration.seconds(10))
                .memorySize(512)
                .build();
        keySecret.grantRead(tokenVendingMachineHandler);

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
        
        // userinfo endpoint
        var userinfoMethod = api.getRoot().addResource("userinfo").addMethod(
                "GET", new LambdaIntegration(tokenVendingMachineHandler),
                MethodOptions.builder().requestValidator(validator).build());

        // suppress public endpoints findings
        NagSuppressions.addResourceSuppressions(openidConfigMethod, List.of(
                NagPackSuppression.builder()
                        .id("AwsSolutions-APIG4")
                        .reason("The OIDC discovery endpoint must be publicly accessible without authorization as per OpenID Connect specification. This is required for OIDC client discovery and is protected by WAF rate limiting.")
                        .build(),
                NagPackSuppression.builder()
                        .id("AwsSolutions-COG4")
                        .reason("Cognito authorization cannot be used on OIDC discovery endpoints as it would break the OIDC protocol requirements for public metadata access.")
                        .build()
        ));
        NagSuppressions.addResourceSuppressions(jwksMethod, List.of(
                NagPackSuppression.builder()
                        .id("AwsSolutions-APIG4")
                        .reason("The JWKS endpoint must be publicly accessible to provide JWT verification keys. This is a requirement of the OpenID Connect specification and is protected by WAF rate limiting.")
                        .build(),
                NagPackSuppression.builder()
                        .id("AwsSolutions-COG4")
                        .reason("Cognito authorization cannot be used on JWKS endpoints as this would prevent OIDC relying parties from validating JWT signatures as required by the protocol.")
                        .build()
        ));
        NagSuppressions.addResourceSuppressions(tokenMethod, List.of(
                NagPackSuppression.builder()
                        .id("AwsSolutions-APIG4")
                        .reason("The token endpoint must be directly accessible to clients to enable the OIDC authorization flow. Email verification and DynamoDB allowlist provide authentication and authorization instead.")
                        .build(),
                NagPackSuppression.builder()
                        .id("AwsSolutions-COG4")
                        .reason("Cognito authorization is not applicable as this endpoint implements its own authentication via email verification against the DynamoDB allowlist.")
                        .build()
        ));
        NagSuppressions.addResourceSuppressions(userinfoMethod, List.of(
                NagPackSuppression.builder()
                        .id("AwsSolutions-APIG4")
                        .reason("The UserInfo endpoint follows OIDC standards which require it to be accessible by any client with a valid token. Authorization is performed by validating the JWT token in the Authorization header.")
                        .build(),
                NagPackSuppression.builder()
                        .id("AwsSolutions-COG4")
                        .reason("Cognito authorization is not applicable as this endpoint implements its own token-based authentication by validating the JWT in the request.")
                        .build()
        ));

        // 11) Grant invoke
        openIdConfigurationHandler.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));
        jwksEndpointHandler.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));
        tokenVendingMachineHandler.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));

        // 12) Outputs
        String issuer = api.getUrl().replaceAll("/+$", "");
        new CfnOutput(this, "TvmIssuerUrl", CfnOutputProps.builder()
                .value(issuer)
                .exportName("TvmIssuerUrl").build());
        new CfnOutput(this, "TvmAudience", CfnOutputProps.builder()
                .value(audience)
                .exportName("TvmAudience").build());
        new CfnOutput(this, "TvmApiUrl", CfnOutputProps.builder()
                .value(api.getUrl())
                .exportName("TvmApiUrl").build());
        new CfnOutput(this, "TvmEmailAllowlistTable", CfnOutputProps.builder()
                .value(emailAllowlistTable.getTableName())
                .exportName("TvmEmailAllowlistTable").build());
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
