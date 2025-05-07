package com.amazon.stacks;

import com.amazon.policies.SearchWithTipRolePolicy;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Aspects;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.constructs.Construct;
import software.amazon.awscdk.services.iam.OpenIdConnectProvider;
import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import io.github.cdklabs.cdknag.NagSuppressions;
import io.github.cdklabs.cdknag.NagPackSuppression;

import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.Effect;

import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Code;

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

import software.amazon.awscdk.Duration;

import java.util.List;
import java.util.Map;

public class SearchStack extends Stack {
    public SearchStack(final Construct scope, final String id, final OpenIdConnectProvider oidcProvider, 
                     final String tvmApiUrl, final String tvmAudience, final String applicationId, final String retrieverId) {
        this(scope, id, oidcProvider, tvmApiUrl, tvmAudience, applicationId, retrieverId, null);
    }

    public SearchStack(final Construct scope, final String id, final OpenIdConnectProvider oidcProvider,
                     final String tvmApiUrl, final String tvmAudience, final String applicationId, final String retrieverId,
                     final StackProps props) {
        super(scope, id, props);

        //
        // 1) Use the parameters from upstream stacks
        //
        String tvmOidcProviderArn = oidcProvider.getOpenIdConnectProviderArn();
        String tvmIssuerUrl       = tvmApiUrl.replaceAll("/+$", ""); // strip trailing slash if present
        
        // Get token endpoint from TVM API URL
        String tokenEndpoint = tvmApiUrl.endsWith("/")
                ? tvmApiUrl + "token"
                : tvmApiUrl + "/token";

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
        // 3) Create a Lambda execution role with least privilege for the handler itself
        //
        // Create the Lambda execution role first (we'll add log permissions later)
        Role lambdaExecRole = Role.Builder.create(this, "SearchLambdaExecRole")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .build();

        //
        // 4) Define your SearchHandler function
        //
        Function searchFn = Function.Builder.create(this, "SearchHandler")
                .functionName("SearchHandler")
                .runtime(Runtime.JAVA_21)
                .handler("com.amazon.SearchHandler::handleRequest")
                // Path must be relative to the project root directory
                .code(Code.fromAsset("services/search/target/search-1.0.0.jar"))
                .role(lambdaExecRole)
                .timeout(Duration.seconds(30))
                .environment(Map.of(
                        "TOKEN_ENDPOINT",    tokenEndpoint,
                        "ROLE_ARN",          roleArn,
                        "QBUS_APP_ID",       applicationId,
                        "QBUS_RETRIEVER_ID", retrieverId
                ))
                .build();

        // Create CloudWatch log group using the actual function name
        LogGroup searchLogGroup = LogGroup.Builder.create(this, "SearchHandlerLogs")
                .logGroupName("/aws/lambda/" + searchFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .build();
                
        // Create CloudWatch logs policy with specific log group ARN (least privilege)
        PolicyStatement lambdaLogsPolicy = PolicyStatement.Builder.create()
                .sid("AllowLambdaLogs")
                .effect(Effect.ALLOW)
                .actions(List.of(
                        "logs:CreateLogStream", 
                        "logs:PutLogEvents"
                ))
                .resources(List.of(
                        searchLogGroup.getLogGroupArn() + ":*"
                )) // Scoped to specific Lambda log group
                .build();
                
        // Add the CloudWatch logs policy to the Lambda role
        lambdaExecRole.addToPolicy(lambdaLogsPolicy);
        
        // Add suppressions for the ':*' suffix on log group ARNs
        NagSuppressions.addResourceSuppressions(
            lambdaExecRole, 
            List.of(
                NagPackSuppression.builder()
                    .id("AwsSolutions-IAM5")
                    .reason("LogGroup ARN needs a ':*' suffix to allow writing to all streams in that specific group.")
                    .build()
            ),
            true  // Apply to child nodes
        );

        // Allow API Gateway to invoke our Lambda
        searchFn.grantInvoke(new ServicePrincipal("apigateway.amazonaws.com"));

        //
        // 5) Expose it via API Gateway
        //
        // Create a log group for access logs
        LogGroup apiAccessLogs = LogGroup.Builder.create(this, "SearchApiAccessLogs")
                .logGroupName("/aws/apigateway/SearchRelevantContentApi-access-logs")
                .retention(RetentionDays.ONE_MONTH)
                .build();
        
        // Create the REST API first with logging options
        RestApi api = RestApi.Builder.create(this, "SearchApi")
                .restApiName("SearchRelevantContentApi")
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
        RequestValidator validator = RequestValidator.Builder.create(this, "SearchApiValidator")
                .restApi(api)
                .requestValidatorName("body-and-params-validator")
                .validateRequestBody(true)
                .validateRequestParameters(true)
                .build();
                
        // Apply removal policy to deployment stage
        api.getDeploymentStage().applyRemovalPolicy(RemovalPolicy.RETAIN);
                
        // Create a WAF Web ACL to protect the API
        CfnWebACL wafAcl = CfnWebACL.Builder.create(this, "SearchApiWafAcl")
                .name("SearchApiWafAcl")
                .scope("REGIONAL")
                .defaultAction(CfnWebACL.DefaultActionProperty.builder()
                        .allow(CfnWebACL.AllowActionProperty.builder().build())
                        .build())
                .visibilityConfig(CfnWebACL.VisibilityConfigProperty.builder()
                        .cloudWatchMetricsEnabled(true)
                        .metricName("SearchApiWafAcl")
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
        CfnWebACLAssociation.Builder.create(this, "SearchApiWafAssociation")
                .resourceArn("arn:aws:apigateway:" + Stack.of(this).getRegion() + 
                            "::/restapis/" + api.getRestApiId() + 
                            "/stages/" + stageName)
                .webAclArn(wafAcl.getAttrArn())
                .build();
                
        // Add suppression for the WAF association (APIG3)
        NagSuppressions.addResourceSuppressions(
            api.getDeploymentStage(), 
            List.of(
                NagPackSuppression.builder()
                    .id("AwsSolutions-APIG3")
                    .reason("WAF is associated via CfnWebACLAssociation, but CDK cannot detect this in the L2 construct")
                    .build()
            )
        );

        // Add the search endpoint
        var searchResource = api.getRoot().addResource("search");
        var searchMethod = searchResource.addMethod("POST", 
                new LambdaIntegration(searchFn),
                MethodOptions.builder()
                        .requestValidator(validator)
                        .build());
        
        // Add suppressions for the search endpoint security findings
        // This API uses OAuth tokens for authentication, not API Gateway auth
        NagSuppressions.addResourceSuppressions(
            searchMethod,
            List.of(
                NagPackSuppression.builder()
                    .id("AwsSolutions-APIG4")
                    .reason("The search API authenticates with the OAuth token passed in the request body, not with API Gateway authorizers")
                    .build(),
                NagPackSuppression.builder()
                    .id("AwsSolutions-COG4")
                    .reason("The search API uses custom token verification with Amazon Q Business trusted identity, not Cognito")
                    .build()
            )
        );

        //
        // 6) Export the URL so downstream stacks/users can pick it up
        //
        new CfnOutput(this, "SearchApiUrl", CfnOutputProps.builder()
                .exportName("SearchApiUrl")
                .value(api.getUrl() + "search")
                .build());
    }
}