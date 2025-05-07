package com.amazon.stacks;

import com.amazon.policies.SearchWithTipRolePolicy;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Aspects;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.constructs.Construct;
import software.amazon.awscdk.services.iam.OpenIdConnectProvider;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.LambdaIntegrationOptions;
import software.amazon.awscdk.services.apigateway.CorsOptions;
import software.amazon.awscdk.services.apigateway.Cors;
import software.amazon.awscdk.services.apigateway.RequestValidator;
import software.amazon.awscdk.services.apigateway.Method;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.MethodLoggingLevel;
import software.amazon.awscdk.services.apigateway.AccessLogFormat;
import software.amazon.awscdk.services.apigateway.LogGroupLogDestination;
import software.amazon.awscdk.services.apigateway.Deployment;
import software.amazon.awscdk.services.apigateway.Stage;
import software.amazon.awscdk.services.wafv2.CfnWebACL;
import software.amazon.awscdk.services.wafv2.CfnWebACLAssociation;
import io.github.cdklabs.cdknag.AwsSolutionsChecks;
import io.github.cdklabs.cdknag.NagSuppressions;
import io.github.cdklabs.cdknag.NagPackSuppression;

import java.util.List;
import java.util.Map;

public class SearchStack extends Stack {

    public SearchStack(final Construct scope, final String id,
                       final OpenIdConnectProvider oidcProvider,
                       final String tvmApiUrl,
                       final String tvmAudience,
                       final String applicationId,
                       final String retrieverId) {
        this(scope, id, oidcProvider, tvmApiUrl, tvmAudience, applicationId, retrieverId, null);
    }

    public SearchStack(final Construct scope, final String id,
                       final OpenIdConnectProvider oidcProvider,
                       final String tvmApiUrl,
                       final String tvmAudience,
                       final String applicationId,
                       final String retrieverId,
                       final StackProps props) {
        super(scope, id, props);

        // 1) Build endpoints & URLs
        String tvmIssuerUrl = tvmApiUrl.replaceAll("/+$", "");
        String tokenEndpoint = tvmApiUrl.endsWith("/") ? tvmApiUrl + "token" : tvmApiUrl + "/token";

        // 2) STS-assumable role
        Role searchWithTipRole = SearchWithTipRolePolicy.create(
                this, "SearchWithTipRole",
                oidcProvider.getOpenIdConnectProviderArn(),
                tvmIssuerUrl,
                tvmAudience,
                applicationId
        );
        String roleArn = searchWithTipRole.getRoleArn();

        // 3) Lambda execution role + logs policy
        Role lambdaExecRole = Role.Builder.create(this, "SearchLambdaExecRole")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .build();

        LogGroup searchLogGroup = LogGroup.Builder.create(this, "SearchHandlerLogs")
                .logGroupName("/aws/lambda/SearchHandler")
                .retention(RetentionDays.ONE_MONTH)
                .build();

        PolicyStatement lambdaLogsPolicy = PolicyStatement.Builder.create()
                .sid("AllowLambdaLogs")
                .effect(Effect.ALLOW)
                .actions(List.of("logs:CreateLogStream", "logs:PutLogEvents"))
                .resources(List.of(searchLogGroup.getLogGroupArn() + ":*"))
                .build();
        lambdaExecRole.addToPolicy(lambdaLogsPolicy);
        NagSuppressions.addResourceSuppressions(lambdaExecRole,
                List.of(NagPackSuppression.builder()
                        .id("AwsSolutions-IAM5")
                        .reason("Lambda log group requires ':*' suffix")
                        .build()),
                true
        );

        // 4) Lambda function
        Function searchFn = Function.Builder.create(this, "SearchHandler")
                .functionName("SearchHandler")
                .runtime(Runtime.JAVA_21)
                .handler("com.amazon.SearchHandler::handleRequest")
                .code(Code.fromAsset("services/search/target/search-1.0.0.jar"))
                .role(lambdaExecRole)
                .timeout(Duration.seconds(60))
                .environment(Map.of(
                        "TOKEN_ENDPOINT", tokenEndpoint,
                        "ROLE_ARN", roleArn,
                        "QBUS_APP_ID", applicationId,
                        "QBUS_RETRIEVER_ID", retrieverId
                ))
                .build();

        // 5) API Gateway without auto-deploy
        LogGroup apiAccessLogs = LogGroup.Builder.create(this, "SearchApiAccessLogs")
                .logGroupName("/aws/apigateway/SearchRelevantContentApi-access-logs")
                .retention(RetentionDays.ONE_MONTH)
                .build();

        RestApi api = RestApi.Builder.create(this, "SearchApi")
                .restApiName("SearchRelevantContentApi")
                .deploy(false)
                .defaultCorsPreflightOptions(CorsOptions.builder()
                        .allowOrigins(Cors.ALL_ORIGINS)
                        .allowMethods(Cors.ALL_METHODS)
                        .allowHeaders(List.of("Content-Type", "Authorization"))
                        .build())
                .build();

        NagSuppressions.addResourceSuppressions(api,
                List.of(NagPackSuppression.builder()
                        .id("AwsSolutions-IAM4")
                        .reason("API Gateway needs AWS-managed log push policy")
                        .build()),
                true
        );

        RequestValidator validator = RequestValidator.Builder.create(this, "SearchApiValidator")
                .restApi(api)
                .requestValidatorName("body-and-params-validator")
                .validateRequestBody(true)
                .validateRequestParameters(true)
                .build();

        // 6) Resource and Method
        var searchResource = api.getRoot().addResource("search");
        Method searchMethod = searchResource.addMethod("POST",
                new LambdaIntegration(searchFn, LambdaIntegrationOptions.builder()
                        .allowTestInvoke(false)
                        .build()),
                MethodOptions.builder().requestValidator(validator).build()
        );
        NagSuppressions.addResourceSuppressions(searchMethod,
                List.of(
                        NagPackSuppression.builder()
                                .id("AwsSolutions-APIG4")
                                .reason("OAuth token used, not API Gateway auth")
                                .build(),
                        NagPackSuppression.builder()
                                .id("AwsSolutions-COG4")
                                .reason("Custom token verification, not Cognito")
                                .build()
                )
        );

        // 7) Manual Deployment and Stage
        Deployment deployment = Deployment.Builder.create(this, "SearchApiDeployment")
                .api(api)
                .build();

        Stage stage = Stage.Builder.create(this, "SearchApiStage")
                .deployment(deployment)
                .stageName("prod")
                .accessLogDestination(new LogGroupLogDestination(apiAccessLogs))
                .accessLogFormat(AccessLogFormat.clf())
                .loggingLevel(MethodLoggingLevel.INFO)
                .dataTraceEnabled(true)
                .build();
        // Retain stage on destroy
        stage.applyRemovalPolicy(RemovalPolicy.RETAIN);

        // 8) WAF association
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

        CfnWebACLAssociation.Builder.create(this, "SearchApiWafAssociation")
                .resourceArn(stage.getStageArn())
                .webAclArn(wafAcl.getAttrArn())
                .build();
        NagSuppressions.addResourceSuppressions(stage,
                List.of(NagPackSuppression.builder()
                        .id("AwsSolutions-APIG3")
                        .reason("WAF attached via CfnWebACLAssociation")
                        .build())
        );

        // 9) Export URL
        String baseUrl = "https://" + api.getRestApiId() + ".execute-api." + getRegion() + "/prod/search";
        new CfnOutput(this, "SearchApiUrl", CfnOutputProps.builder()
                .exportName("SearchApiUrl")
                .value(baseUrl)
                .build());
    }
}
