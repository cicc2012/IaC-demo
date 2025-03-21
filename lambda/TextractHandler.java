package edu.uco.cicc;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextRequest;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;
import software.amazon.awssdk.services.textract.model.Document;
import software.amazon.awssdk.services.textract.model.S3Object;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// AWS Lambda Runtime Interface v2
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

/**
 * Note: The Lambda runtime library (com.amazonaws.services.lambda) is separate from the AWS SDK.
 * Even when using AWS SDK v2, Lambda functions still use the same runtime interface.
 * AWS has not released a v2 version of the Lambda runtime interface as of March 2025.
 */
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
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        // Add CORS headers
        headers.put("Access-Control-Allow-Origin", "*"); // Allow all origins
        headers.put("Access-Control-Allow-Methods", "POST, OPTIONS"); // Allow POST and OPTIONS methods
        headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization"); // Allow specific headers
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
            
            // context.getLogger().log("Processing image from bucket: " + bucketName + ", objectKey: " + objectKey);
            
            // Configure Textract request using SDK v2 classes
            // S3Object s3Object = S3Object.builder()
            //         .bucket(bucketName)
            //         .name(objectKey)
            //         .build();
            
            // Document document = Document.builder()
            //         .s3Object(s3Object)
            //         .build();
            
            // DetectDocumentTextRequest detectRequest = DetectDocumentTextRequest.builder()
            //         .document(document)
            //         .build();
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
            // context.getLogger().log("Textract Response: " + result.toString());
            
            // Process the result
            StringBuilder extractedText = new StringBuilder();
            
            for (Block block : result.blocks()) {
                // context.getLogger().log("Block Type: " + block.blockType());
                if (block.blockType() == BlockType.LINE || block.blockType() == BlockType.WORD) {
                    // context.getLogger().log("Processed text from image: " + block.text());
                    extractedText.append(block.text()).append("\n");
                }
            }
            
            // Create response
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("text", extractedText.toString());
            // context.getLogger().log("Extracted Text: " + extractedText.toString());
            
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

