# Amazon Q Business Index Search API Java Sample

Build a secure Amazon Q Business Index search API with Java CDK Stack

Sample implementation showing how to set up a Trusted Token Issuer with Lambda and API Gateway, registering an Amazon Q Business Application with OIDC IdP integration, and deploying a search microservice. The sample is fully deployable via AWS CDK and follows AWS security best practices.

Using this sample, you will be able to:
1. Stand up a secure Token Vending Machine (TVM) (Lambda + API Gateway + KMS asymmetric keys)
2. Register an Amazon Q Business Application (OIDC IdP) + Index
3. Deploy a Search microservice (Lambda + API Gateway) that fetches an OIDC token from TVM, calls STS to assume-role-with-web-identity, and invokes Q Business's SearchRelevantContent API

## Repository Layout

```
amazon-qbusiness-index-search-api-java-sample/
├── cdk/                             AWS CDK Java app
│   ├── pom.xml                      CDK project POM
│   ├── src/main/java/com/amazon/
│   │   ├── policies/                IAM policy helper classes
│   │   └── stacks/
│   │       ├── QBusinessApp.java    CDK application entry point
│   │       ├── QBusinessStack.java  Q Business configuration
│   │       ├── TokenVendingMachineStack.java  TVM infrastructure
│   │       └── SearchStack.java     Search infrastructure
├── services/
│   ├── TokenVendingMachine/         Token Vending Machine service
│   │   ├── pom.xml
│   │   └── src/main/java/com/amazon/
│   │       ├── OpenIdConfigurationHandler.java
│   │       ├── JwksEndpointHandler.java
│   │       ├── TokenVendingMachineHandler.java
│   │       └── KeyManager.java
│   └── search/                      Search microservice
│       ├── pom.xml
│       └── src/main/java/com/amazon/
│           └── SearchHandler.java
├── cdk.json                         CDK application configuration
├── README.md                        you're here!
└── THREAT-MODEL.md                  Security threat model
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

1. Build all projects:
```bash
# Build the project from the root directory
mvn clean package
```

2. Bootstrap CDK (first-time only):
```bash
cdk bootstrap
```

3. Deploy the full stack or individual stacks:

**Option 1: Deploy all stacks at once**
```bash
# From the project root
cdk deploy --all
```

**Option 2: Deploy stacks incrementally**

a. Deploy Token Vending Machine Stack:
```bash
cdk deploy TokenVendingMachineStack --outputs-file tvm-outputs.json
```
Outputs: TvmApiUrl, TvmIssuerUrl, TvmOidcProviderArn, TvmAudience

b. Deploy QBusiness Stack:
```bash
cdk deploy QBusinessStack --outputs-file qbus-outputs.json
```
Inputs: from tvm-outputs.json  
Outputs: QBusinessApplicationId, QBusinessRetrieverId, QBusinessRoleArn

c. Deploy Search Stack:
```bash
cdk deploy SearchStack
```
Inputs: from both tvm-outputs.json & qbus-outputs.json  
Outputs: SearchApiUrl

The `cdk.json` file configures the CDK CLI to use QBusinessApp.java as the entry point, which includes all three stacks.

## Test End-to-End

### Option 1: If You Deployed Stacks Incrementally

If you deployed the stacks incrementally and saved outputs to files:

1. Get an OIDC token from TVM:
```bash
TVM_API=$(jq -r '.TokenVendingMachineStack.TvmApiUrl' tvm-outputs.json)
TOKEN=$(curl -s -X POST $TVM_API/token -H "Content-Type: application/json" -d '{"email":"you@example.com"}' | jq -r .id_token)
```

2. Call the Search API:
```bash
APP_ID=$(jq -r '.QBusinessStack.QBusinessApplicationId' qbus-outputs.json)
RET_ID=$(jq -r '.QBusinessStack.QBusinessRetrieverId' qbus-outputs.json)
SEARCH_API=$(cdk output -o SearchStack SearchApiUrl)
curl -s -X POST $SEARCH_API -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"email":"you@example.com","query":"quarterly","applicationId":"'"$APP_ID"'","retrieverId":"'"$RET_ID"'"}'
```

### Option 2: If You Deployed All Stacks at Once

If you deployed all stacks at once, you'll need to retrieve the stack outputs directly:

1. Get necessary endpoints and IDs using CloudFormation:

```bash
# Get endpoints and IDs using CloudFormation
TVM_API=$(aws cloudformation describe-stacks --stack-name TokenVendingMachineStack --query "Stacks[0].Outputs[?OutputKey=='TvmApiUrl'].OutputValue" --output text)
APP_ID=$(aws cloudformation describe-stacks --stack-name QBusinessStack --query "Stacks[0].Outputs[?OutputKey=='QBusinessApplicationId'].OutputValue" --output text)
RET_ID=$(aws cloudformation describe-stacks --stack-name QBusinessStack --query "Stacks[0].Outputs[?OutputKey=='QBusinessRetrieverId'].OutputValue" --output text)
SEARCH_API=$(aws cloudformation describe-stacks --stack-name SearchStack --query "Stacks[0].Outputs[?OutputKey=='SearchApiUrl'].OutputValue" --output text)
```

Alternatively, you can use CDK output commands:

```bash
# Get endpoints and IDs using CDK
TVM_API=$(cdk output -o TokenVendingMachineStack TvmApiUrl)
APP_ID=$(cdk output -o QBusinessStack QBusinessApplicationId)
RET_ID=$(cdk output -o QBusinessStack QBusinessRetrieverId)
SEARCH_API=$(cdk output -o SearchStack SearchApiUrl)
```

2. Get a token:
```bash
TOKEN=$(curl -s -X POST $TVM_API/token -H "Content-Type: application/json" -d '{"email":"you@example.com"}' | jq -r .id_token)
```

3. Call the search API:
```bash
curl -s -X POST $SEARCH_API -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"email":"you@example.com","query":"quarterly","applicationId":"'"$APP_ID"'","retrieverId":"'"$RET_ID"'"}'
```

> **Note:** If you encounter timeouts with the API Gateway, this is normal. The Lambda function may still be executing and completing successfully in the backend. Check the CloudWatch logs for the SearchHandler Lambda function to confirm that the search was processed correctly.

## Cleanup

```bash
# Option 1: Remove all stacks at once
cdk destroy --all

# Option 2: Remove stacks individually
cdk destroy SearchStack
cdk destroy QBusinessStack
cdk destroy TokenVendingMachineStack
```

## License

Apache 2.0 © Amazon.com, Inc. or its affiliates