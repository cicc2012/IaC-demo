# AWS IaC Demo

To demonstrate how to connect an AWS Amplify app, an API, and AWS Lambda functions to create a fully functioning system where you can upload an image to the Amplify app, trigger a Lambda function to extract text from the image using **AWS Textract**, and integrate this process into a **CI/CD pipeline**, we'll go through a complete setup using **AWS SDK** and **AWS CDK**.

About details of AWS CDK V2, you can find more information [here](https://docs.aws.amazon.com/cdk/v2/guide/work-with-cdk-java.html).

### Steps Overview:
1. **Set up an Amplify App** to host a web frontend where you can upload the image.
2. **Create a Lambda Function** to call AWS Textract to extract text from the image.
3. **Create an API Gateway** to trigger the Lambda function from the frontend.
4. **Set up CI/CD pipeline** using AWS Amplify for automated build and deploy.
5. **Test** using CloudWatch to track the logs.

Here’s how to structure everything.
#### System Architecture

```text
┌───────────────┐     ┌───────────────┐    ┌───────────────┐     ┌───────────────┐
│  AWS Amplify  │     │ API Gateway   │    │ AWS Lambda    │     │ AWS Textract  │
│  Frontend     │───▶│ REST API      │───▶│  Function     │───▶│ Service       │
└───────────────┘     └───────────────┘    └───────────────┘     └───────────────┘
        ▲                                         │
        │                                         │
        │                                         ▼
┌───────────────┐                        ┌───────────────┐
│   CI/CD       │                        │  S3 Bucket    │
│   with Github │                        │  (Images)     │
└───────────────┘                        └───────────────┘
```

## Components Overview

- **AWS Amplify Frontend**: React application that allows users to submit S3 image URLs
- **API Gateway**: REST API that accepts requests from the frontend
- **AWS Lambda**: Java function that processes the image URL and calls AWS Textract
- **AWS Textract**: Service that extracts text from images
- **S3 Bucket**: Storage for images (pre-existing)
- **CodePipeline**: CI/CD pipeline for automated deployment
---

### 1. **Prerequisites**:
Before we begin, make sure you have the following:
- **AWS CLI** installed and configured on your machine.
- **Java Development Kit (JDK)** installed on your machine (for AWS SDK and CDK).
- **VS Code** installed.
- **Node.js and npm** installed (for AWS Amplify setup).
- **AWS CDK** installed: `npm install -g aws-cdk`.

You’ll need an **AWS account** and the required permissions to deploy AWS resources like Amplify, Lambda, API Gateway, etc.

**Install Node.js**: Download and install Node.js from [nodejs.org](https://nodejs.org/).
If you haven't install AWS CLI:
- Download and install the AWS CLI from [AWS CLI Installation Guide](https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2.html).
    - Configure it with your credentials:

```bash
aws configure
```
**Install AWS CDK**:

```bash
npm install -g aws-cdk
```
**Install Maven**: Download Maven from [Maven's website](https://maven.apache.org/) if not already installed.

**IAM** Roles: Make sure your accound has necessary permissions. For simplicity, you can give your accound full access / administrator role for the following services, similar to:
- iam
- ssm
- ecr
- s3
- cloudformation
- amplify
- api gateway
- textract
- lambda

**Verify IAM User or Role**: You can verify it by
```bash
aws sts get-caller-identity
```

---

### 2. **Setting up the Project in VS Code**:
In this demo, we will use AWS CDK for infrastructure and **AWS SDK for Java** to interact with the Lambda function. Let’s create a new CDK project in Java.

#### Step 2.1: Create a new CDK Project

1. **Set up VS Code**: Install the Java Extension Pack in VS Code. Open **VS Code**.

2. Open the terminal and run the following command to create a new CDK Java project:
   ```bash
   mkdir amplify-textract-demo
   cd amplify-textract-demo
   cdk init app --language java
   ```

3. Add required CDK dependencies to your `pom.xml`.
You can use the attached [pom.xml](pom.xml) as reference.

#### Step 2.2: File Organization
Inside the `src/main/java/edu/uco/cicc/` directory, create a file called `AmplifyTextractDemoStack.java` to define your CDK infrastructure.

The subfolders /edu/uco/cicc/ are aligned with the package management in Java. You can find the package at the top of each Java file.

You can find a reference of this stack [here](src/AmplifyTextractDemoStack.java).

Let's organize the files of this demo as below:

```text
amplify-textract-demo/
├── pom.xml                     # Parent POM
├── src/
│   └── main/
│       └── java/
│           └── edu/
│               └── uco/
│                   └── cicc/
│                       ├── AmplifyTextractDemoStack.java
│                       └── AmplifyTextractDemoApp.java
├── lambda/                     # Directory for Lambda functions
│   └── textract/               # Textract Lambda function module
│       ├── pom.xml             # Textract POM
│       ├── src/
│       │   └── main/
│       │       └── java/
│       │           └── edu/
│       │               └── uco/
│       │                   └── cicc/
│       │                       └── TextractHandler.java
│       └── target/             # Output directory for the compiled JAR
│           └── textract-processor.jar
```


#### Step 2.3: About Lambda Function for Textract

Create a Lambda function in Java that uses AWS Textract to extract text from an image uploaded to the S3 bucket.

- Create a directory `lambda/textract/src/main/java/edu/uco/cicc/` and add a `TextractHandler.java` class to process the image with AWS Textract.

```java
public class TextractHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    
    private final TextractClient textractClient;
    private final ObjectMapper objectMapper;
    
    // Regular expression to extract bucket name and key from S3 URL
    private static final Pattern S3_URL_PATTERN = Pattern.compile(
            "https://([^.]+)\\.s3\\.[^/]+\\.amazonaws\\.com/(.*)");
    
    public TextractHandler() {
        this.textractClient = TextractClient.builder()
                .region(Region.US_EAST_1) // Update with your region
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context    context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        // Add CORS headers
        headers.put("Access-Control-Allow-Origin", "*"); // Allow all origins
        headers.put("Access-Control-Allow-Methods", "POST, OPTIONS"); // Allow POST and OPTIONS     methods
        headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization"); // Allow    specific headers
        response.setHeaders(headers);

        try {
            // Parse request body to get the S3 image URL
            JsonNode requestBody = objectMapper.readTree(input.getBody());
            String imageUrl = requestBody.get("s3_url").asText();

            // Extract bucket and key from S3 URL
            Matcher matcher = S3_URL_PATTERN.matcher(imageUrl);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Invalid S3 URL format");
            }

            String bucketName = matcher.group(1);
            String objectKey = matcher.group(2);

            // Configure Textract request
            DetectDocumentTextRequest detectRequest = DetectDocumentTextRequest.builder()
                    .document(Document.builder()
                            .s3Object(S3Object.builder()
                                    .bucket(bucketName)
                                    .name(objectKey)
                                    .build())
                            .build())
                    .build();

            // Call Textract service to extract text
            DetectDocumentTextResponse result = textractClient.detectDocumentText(detectRequest);

            // Process the result
            StringBuilder extractedText = new StringBuilder();
            for (Block block : result.blocks()) {
                if (block.blockType() == BlockType.LINE || block.blockType() == BlockType.WORD) {
                    extractedText.append(block.text()).append("\n");
                }
            }

            // Create response
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("text", extractedText.toString());
            response.setStatusCode(200);
            response.setBody(objectMapper.writeValueAsString(responseBody));

        } catch (Exception e) {
            context.getLogger().log("Error processing request: " + e.getMessage());

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());

            try {
                response.setStatusCode(500);
                response.setBody(objectMapper.writeValueAsString(errorResponse));
            } catch (Exception ex) {
                response.setStatusCode(500);
                response.setBody("{\"error\": \"Internal server error\"}");
            }
        }

        return response;
    }
}
```

In this Lambda handler:
- The image is analyzed using **AWS Textract**'s `detectDocumentText` method.
- The extracted text is returned from the Lambda.

#### Step 2.4: Set Up AWS Amplify Frontend

1. **Create an Amplify App**: 

We can go to Amplify web console, and create an app from your Github repository.

   - In the Amplify Console, click **Create new aPP**."
   - Select **GitHub** as the repository source.
   - You may need to authorize AWS Amplify to access your GitHub account. Grant the necessary permissions. You'll be redirected to GitHub.com, where you'll be asked to grant permission for AWS Amplify to access your GitHub account. You can choose to apply  to all repositories or select specific repositories.
   - Select the repository that contains your HTML project.
   - Choose the branch you want to deploy (e.g., `main` or `master`).
   - "App settings": create a name for your app (unique under your account) 
   - Configure build settings: You don't need a build process for a single HTML file, so there's no need for a build command. Setting the build output directory to / tells Amplify that your deployable files (in this case, just the index.html) are in the root of your project.


2. **CI/CD**: AWS Amplify Console automatically sets up the CI/CD pipeline when you connect a GitHub repository.

---

### 3. **Deploying the Stack**:

#### Compile the Code

So far, the code for the backend is ready.

##### 3.1 Compile the Lambda Function

 You can use the following command to build the package for your lambda function:
```bash
cd ./lambda/textract
mvn clean package
mv ./target/textract-1.0-SNAPSHOT.jar ./target/textract.jar
cd ../..
```
Please **note**:
1. on Windows system, repalce `mv` command to `ren`.
2. the last command is to leave lambda/textract and go to the home folder of this project, where you can find cdk.out and cdk.json.

You will see a file named similar to `textract.jar` under ./lambda/textract/target. If it's name is diffrent from `textract.jar`, then change it to textract.jar.
This will be the clue for the code in your cdk stack (inside [AmplifyTextractDemoStack.java](src/AmplifyTextractDemoStack.java)) to be covered: 
```java
             code(Code.fromAsset("./lambda/textract/target/textract.jar"))
```
from the function:
```java
         // 1. Create Lambda function for processing images with Textract
         Function textractFunction = Function.Builder.create(this, "cicc-TextractFunction")
            .runtime(Runtime.JAVA_17)
            .code(Code.fromAsset("./lambda/textract/target/textract.jar"))
            .handler("edu.uco.cicc.TextractHandler::handleRequest")
            .memorySize(1024)
            .timeout(Duration.seconds(30))
            .build();
```

##### 3.2 Compile Entire Project

Once everything is ready, you can deploy the CDK stack with the following commands (make sure you are currently in your home folder of the project, not under lambda/textract):

```bash
mvn clean install
cdk bootstrap  # Initializes resources in your AWS environment
cdk deploy     # Deploys the stack
```

Please **note**: You only need to run `cdk bootstrap` **once** per AWS account and region, or if you change the AWS environment (e.g., switch to a new account or region). Once this is done, you will see a stack named CDKToolkit in the CloudFormation web console. 

After running `cdk deploy`, you will see other stacks in the CloudFormation web console.

So far, this is the initial pipeline to compile the code. When you have future changes, replace "mvn clean install" by **"mvn clean package"**, **without** "cdk boostrap".

---

### 4. **CI/CD Pipeline**:
When you push changes to the Git repository connected to AWS Amplify, the CI/CD pipeline will be triggered:
- **Frontend**: The Amplify app is built and deployed to the Amplify Console.
- For deployments using AWS CloudFormation, CLI, or SDKs, you'll need to manually install the Amplify GitHub App and generate a personal access token in your GitHub account. But this is not recommended, because of potential security risks, e.g., exposing the token directly in Github or other AWS services.


---

### 5. Test 

You can do the same tests as your previous projects. 

Here we can practice a new logging system.

The log generated by `context.getLogger().log(...)` in your AWS Lambda function is sent to **Amazon CloudWatch Logs**. Each AWS Lambda function automatically writes its logs to a dedicated log group in CloudWatch.

#### **Steps to Find the Logs**

There are some commented statements in the source code of the Lambda function, for logging. You can use some of them for debugging. These logs will be saved to another AWS service: CloudWatch. 

##### **5.1. Open the AWS Management Console**
1. Go to the [AWS Management Console](https://aws.amazon.com/console/).
2. Navigate to the **CloudWatch** service.

##### **5.2. Locate the Log Group for Your Lambda Function**
1. In the CloudWatch console, click on **Logs** in the left-hand menu.
2. Look for the log group associated with your Lambda function. The log group name will follow this pattern:
   ```
   /aws/lambda/<LambdaFunctionName>
   ```
   For example, if your Lambda function is named `cicc-TextractFunction`, the log group will be:
   ```
   /aws/lambda/cicc-TextractFunction
   ```

##### **5.3. View the Log Streams**
1. Click on the log group for your Lambda function.
2. Inside the log group, you will see multiple **log streams**. Each log stream corresponds to an invocation of your Lambda function.
3. Click on the most recent log stream to view the logs for the latest invocation.

###### **5.4. Search for Your Log Message**
Look for the log message generated by your `context.getLogger().log(...)` statement. For example:
```
Processing image from bucket: <bucket-name>, key: <key-name>
```

If you would like to learn how to debug your Lambda functions locally, you can visit [AWS Serverless Application Model (AWS SAM)](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/what-is-sam.html)



