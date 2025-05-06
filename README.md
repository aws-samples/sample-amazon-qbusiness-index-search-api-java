# amazon-qbusiness-index-search-api-java-sample

Build an Amazon Q Business Index search API with Java CDK Stack

Sample implementation showing how to set up a Trusted Token Issuer with Lambda and API Gateway, registering an Amazon Q Business Application with OIDC IdP integration, and deploying a search microservice. The sample is fully deployable via AWS CDK.

Using this sample, you will be able to:
1. Stand up a Trusted Token Issuer (TVM) (Lambda + API Gateway + KMS)
2. Register an Amazon Q Business Application (OIDC IdP) + Index
3. Deploy a Search microservice (Lambda + API Gateway) that fetches an OIDC token from TVM, calls STS to assume-role-with-web-identity, and invokes Q Business's SearchRelevantContent API

## Repository Layout

```
qbusiness-search-cdk-sample/
├── cdk/                             AWS CDK Java app
│   ├── pom.xml                      CDK project POM
│   └── src/main/java/com/amazon/stacks/
│       ├── TVMStack.java
│       ├── QBusinessStack.java
│       └── SearchStack.java
├── services/
│   ├── tvm/                         Token Vending Machine service
│   │   ├── pom.xml
│   │   └── src/main/java/com/amazon/
│   │       ├── OpenIdConfigurationHandler.java
│   │       ├── JwksEndpointHandler.java
│   │       ├── TokenVendingMachineHandler.java
│   │       └── UserInfoHandler.java
│   └── search/                      Search microservice
│       ├── pom.xml
│       └── src/main/java/com/amazon/
│           └── SearchHandler.java
└── README.md                        you're here!
```

## Architecture

### TVM:

* `GET /.well-known/openid-configuration` → OpenIdConfigFn
* `GET /.well-known/jwks.json`         → JwksFn
* `POST /token`                        → TokenFn
* `GET /userinfo`                      → UserInfoFn

All handlers use the same KMS CMK for RSA signing

### QBusiness:

* TVM OIDC Provider ARN → Application registration
* ApplicationId → created by QBusiness
* IndexId → created by QBusiness and becomes RetrieverId

### Search:

* `POST /search` → SearchFn
* SearchFn calls STS:AssumeRoleWithWebIdentity with the JWT from TVM
* STS response authorizes a temporary role (SearchWithTipRole)
* SearchFn calls qbusiness:SearchRelevantContent with temp credentials

## Prerequisites

* AWS account & CLI credentials
* Java 21 (Corretto or OpenJDK)
* Apache Maven 3.8+
* AWS CDK v2 + Java support (`npm install -g aws-cdk@^2`)
* AWS CLI configured via `aws configure`

## CDK Deployment Steps

1. Bootstrap CDK:
```bash
cd cdk
mvn clean package
cdk bootstrap
```

2. Deploy TVMStack:
```bash
cdk deploy TVMStack --outputs-file tvm-outputs.json
```
Outputs: TvmApiUrl, TvmIssuerUrl, TvmOidcProviderArn, TvmAudience

3. Deploy QBusinessStack:
```bash
cdk deploy QBusinessStack --outputs-file qbus-outputs.json
```
Inputs: from tvm-outputs.json  
Outputs: QBusinessApplicationId, QBusinessRetrieverId, QBusinessRoleArn

4. Deploy SearchStack:
```bash
cdk deploy SearchStack
```
Inputs: from both tvm-outputs.json & qbus-outputs.json  
Outputs: SearchApiUrl

## Test End-to-End

1. Get an OIDC token from TVM:
```bash
TVM_API=$(jq -r .TvmApiUrl tvm-outputs.json)
TOKEN=$(curl -s -X POST $TVM_API/token -H "Content-Type: application/json" -d '{"email":"you@example.com"}' | jq -r .id_token)
```

2. Call the Search API:
```bash
APP_ID=$(jq -r .QBusinessApplicationId qbus-outputs.json)
RET_ID=$(jq -r .QBusinessRetrieverId qbus-outputs.json)
SEARCH_API=$(cdk output SearchApiUrl)
curl -s -X POST $SEARCH_API -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"email":"you@example.com","query":"quarterly","applicationId":"'"$APP_ID"'","retrieverId":"'"$RET_ID"'"}'
```

## Cleanup

```bash
cdk destroy SearchStack
cdk destroy QBusinessStack
cdk destroy TVMStack
```

## License

Apache 2.0 © Amazon.com, Inc. or its affiliates