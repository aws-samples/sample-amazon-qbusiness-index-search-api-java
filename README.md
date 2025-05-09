# Amazon Q Business Index Search API Java Sample

Build a secure Amazon Q Business Index search API with Java CDK Stack

Sample implementation showing how to set up a Trusted Token Issuer with Lambda and API Gateway, registering an Amazon Q Business Application with OIDC IdP integration, and deploying a search microservice. The sample is fully deployable via AWS CDK and follows AWS security best practices.

Using this sample, you will be able to:
1. Stand up a secure Token Vending Machine (TVM) (Lambda + API Gateway + KMS asymmetric keys)
2. Register an Amazon Q Business Application (OIDC IdP) + Index
3. Deploy a Search microservice (Lambda + API Gateway) that fetches an OIDC token from TVM, calls STS to assume-role-with-web-identity, and invokes Q Business's SearchRelevantContent API


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

3. Deploy the full stack:
```bash
# From the project root
cdk deploy --all --outputs-file stack-outputs.json
```

This will deploy all stacks in the correct order and save the outputs for use in testing. The `cdk.json` file configures the CDK CLI to use QBusinessApp.java as the entry point, which includes all stacks.

## Email Allowlist Configuration

The TVM will only issue tokens to email addresses that exist in the DynamoDB allowlist table. To add emails to the allowlist:

1. After deploying the TokenVendingMachineStack, get the table name:
```bash
TABLE_NAME=$(aws cloudformation describe-stacks --stack-name TokenVendingMachineStack --query "Stacks[0].Outputs[?OutputKey=='TvmEmailAllowlistTable'].OutputValue" --output text)
```

2. Add authorized emails to the table:
```bash
aws dynamodb put-item \
  --table-name $TABLE_NAME \
  --item '{"email": {"S": "user@example.com"}, "added_at": {"S": "'$(date -Iseconds)'"}, "active": {"BOOL": true}}'
```

3. Verify the email was added:
```bash
aws dynamodb get-item \
  --table-name $TABLE_NAME \
  --key '{"email": {"S": "user@example.com"}}'
```

You must add email addresses to this table before they can request tokens from the TVM.

## Upload Test Data to Q Business Index

To ensure search results are returned, you need to upload test data to your Q Business index:

1. Get the Q Business application and retriever IDs:
```bash
APP_ID=$(jq -r '.QBusinessStack.QBusinessApplicationId' stack-outputs.json)
RET_ID=$(jq -r '.QBusinessStack.QBusinessRetrieverId' stack-outputs.json)
```

2. Upload the test data using the AWS CLI:
```bash
# Get a token to authenticate with AWS services
TOKEN=$(aws sts get-session-token --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' --output text | xargs -n1)
ACCESS_KEY=$(echo "$TOKEN" | sed -n 1p)
SECRET_KEY=$(echo "$TOKEN" | sed -n 2p)
SESSION_TOKEN=$(echo "$TOKEN" | sed -n 3p)

# Upload the documents to Q Business
aws qbusiness batch-put-document \
  --application-id $APP_ID \
  --index-id $RET_ID \
  --documents file://data/test-data.json \
  --aws-access-key-id $ACCESS_KEY \
  --aws-secret-access-key $SECRET_KEY \
  --aws-session-token $SESSION_TOKEN
```

3. Check the document upload status:
```bash
aws qbusiness list-documents \
  --application-id $APP_ID \
  --index-id $RET_ID \
  --aws-access-key-id $ACCESS_KEY \
  --aws-secret-access-key $SECRET_KEY \
  --aws-session-token $SESSION_TOKEN
```

This will populate your Q Business index with sample documents that will be returned when searching for terms like "quarterly", "report", "marketing", etc.

## Test End-to-End

After deploying the stacks and configuring the email allowlist, you can test the end-to-end functionality:

1. Get necessary endpoints and IDs from the outputs file:
```bash
TVM_API=$(jq -r '.TokenVendingMachineStack.TvmApiUrl' stack-outputs.json)
TABLE_NAME=$(jq -r '.TokenVendingMachineStack.TvmEmailAllowlistTable' stack-outputs.json)
APP_ID=$(jq -r '.QBusinessStack.QBusinessApplicationId' stack-outputs.json)
RET_ID=$(jq -r '.QBusinessStack.QBusinessRetrieverId' stack-outputs.json)
SEARCH_API=$(jq -r '.SearchStack.SearchApiUrl' stack-outputs.json)
```

2. Ensure your test email is in the allowlist:
```bash
aws dynamodb put-item \
  --table-name $TABLE_NAME \
  --item '{"email": {"S": "you@example.com"}, "added_at": {"S": "'$(date -Iseconds)'"}, "active": {"BOOL": true}}'
```

3. Get a token from the TVM:
```bash
TOKEN=$(curl -s -X POST $TVM_API/token -H "Content-Type: application/json" -d '{"email":"you@example.com"}' | jq -r .id_token)
```

4. Call the search API:
```bash
curl -s -X POST $SEARCH_API -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","query":"quarterly","applicationId":"'"$APP_ID"'","retrieverId":"'"$RET_ID"'"}'
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