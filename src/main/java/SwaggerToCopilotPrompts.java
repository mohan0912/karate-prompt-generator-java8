import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.*;
import io.swagger.v3.oas.models.responses.*;
import io.swagger.v3.oas.models.Paths.*;
import io.swagger.v3.parser.OpenAPIV3Parser;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class SwaggerToCopilotPrompts {

    public static void main(String[] args) {
        String filePath = "src/main/resources/openapi.yaml"; // Update this path
        OpenAPI openAPI = new OpenAPIV3Parser().read(filePath);

        if (openAPI == null || openAPI.getPaths() == null) {
            System.err.println("Failed to read OpenAPI spec.");
            return;
        }

        new File("copilot-prompts").mkdirs(); // Ensure output directory exists

        for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();

            for (PathItem.HttpMethod httpMethod : pathItem.readOperationsMap().keySet()) {
                Operation operation = pathItem.readOperationsMap().get(httpMethod);
                String method = httpMethod.toString();
                String summary = operation.getSummary() != null ? operation.getSummary() : "(No summary)";

                StringBuilder prompt = new StringBuilder();
                prompt.append("### Copilot Prompt for API Test Generation\n\n");
                prompt.append("Generate a Jira story for the following API operation. Include both positive and negative test scenarios, handle required and optional inputs, and validate expected responses.\n\n");
                prompt.append("**Method:** ").append(method).append("\n");
                prompt.append("**Path:** ").append(path).append("\n");
                prompt.append("**Summary:** ").append(summary).append("\n\n");

                // Request Parameters
                prompt.append("**Parameters:**\n");
                if (operation.getParameters() != null && !operation.getParameters().isEmpty()) {
                    for (Parameter param : operation.getParameters()) {
                        Schema schema = param.getSchema();
                        String type = schema != null && schema.getType() != null ? schema.getType() : "unknown";
                        prompt.append("- `").append(param.getName()).append("` (in `").append(param.getIn()).append("`) - ")
                              .append(type).append(", ")
                              .append(param.getRequired() != null && param.getRequired() ? "required" : "optional");
                        if (param.getDescription() != null) {
                            prompt.append(" — ").append(param.getDescription());
                        }
                        prompt.append("\n");
                    }
                } else {
                    prompt.append("None\n");
                }
                prompt.append("\n");

                // Request Body
                if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
                    prompt.append("**Request Body:**\n");
                    for (Map.Entry<String, MediaType> entry : operation.getRequestBody().getContent().entrySet()) {
                        prompt.append("- MIME Type: ").append(entry.getKey()).append("\n");
                        Schema schema = entry.getValue().getSchema();
                        if (schema != null) {
                            prompt.append("- Schema: \n");
                            prompt.append("```json\n");
                            prompt.append(generateSchemaJson(schema, openAPI, 1)).append("\n");
                            prompt.append("```\n\n");
                        }
                    }
                }

                // Responses
                prompt.append("**Expected Responses:**\n");
                for (Map.Entry<String, ApiResponse> responseEntry : operation.getResponses().entrySet()) {
                    String statusCode = responseEntry.getKey();
                    ApiResponse response = responseEntry.getValue();
                    prompt.append("- `").append(statusCode).append("`: ")
                          .append(response.getDescription() != null ? response.getDescription() : "")
                          .append("\n");
                    if (response.getContent() != null) {
                        for (Map.Entry<String, MediaType> contentEntry : response.getContent().entrySet()) {
                            prompt.append("  - MIME Type: ").append(contentEntry.getKey()).append("\n");
                            prompt.append("  - Schema: ")
                                  .append(generateFlatSchema(contentEntry.getValue().getSchema(), openAPI)).append("\n");
                            break;
                        }
                    }
                }

                // Write file
                String fileName = "copilot-prompts/" + method + "_" + path.replaceAll("[/{}]", "_") + ".txt";
                writeToFile(fileName, prompt.toString());
                System.out.println("✅ Copilot prompt generated: " + fileName);
            }
        }
    }

    private static void writeToFile(String filename, String content) {
        try {
            FileWriter writer = new FileWriter(filename);
            writer.write(content);
            writer.close();
        } catch (IOException e) {
            System.err.println("❌ Failed to write file: " + filename);
            e.printStackTrace();
        }
    }

    private static Schema<?> resolveRef(Schema<?> schema, OpenAPI openAPI) {
        if (schema.get$ref() != null) {
            String refName = schema.get$ref().substring(schema.get$ref().lastIndexOf("/") + 1);
            return openAPI.getComponents().getSchemas().get(refName);
        }
        return schema;
    }

    private static String generateFlatSchema(Schema<?> schema, OpenAPI openAPI) {
        if (schema == null) return "N/A";
        if (schema.get$ref() != null) schema = resolveRef(schema, openAPI);

        if (schema.getOneOf() != null || schema.getAnyOf() != null || schema.getAllOf() != null) {
            return "Polymorphic schema (oneOf/anyOf/allOf)";
        }

        if ("object".equals(schema.getType()) && schema.getProperties() != null) {
            StringBuilder sb = new StringBuilder("{ ");
            for (Object key : schema.getProperties().keySet()) {
                Schema<?> prop = (Schema<?>) schema.getProperties().get(key);
                sb.append(key).append(": ").append(prop.getType()).append(", ");
            }
            if (sb.length() > 2) sb.setLength(sb.length() - 2);
            sb.append(" }");
            return sb.toString();
        } else if ("array".equals(schema.getType())) {
            return "[ " + generateFlatSchema(schema.getItems(), openAPI) + " ]";
        } else {
            return schema.getType() != null ? schema.getType() : "object";
        }
    }

    private static String generateSchemaJson(Schema<?> schema, OpenAPI openAPI, int indent) {
        if (schema == null) return "{}";
        if (schema.get$ref() != null) schema = resolveRef(schema, openAPI);

        StringBuilder sb = new StringBuilder();
        String space = repeat("  ", indent);

        if (schema.getOneOf() != null || schema.getAnyOf() != null || schema.getAllOf() != null) {
            sb.append(space).append("{\n");
            sb.append(space).append("  // Polymorphic schema using oneOf/anyOf/allOf\n");
            sb.append(space).append("}\n");
            return sb.toString();
        }

        if ("object".equals(schema.getType()) && schema.getProperties() != null) {
            sb.append("{\n");
            for (Object key : schema.getProperties().keySet()) {
                Schema<?> prop = (Schema<?>) schema.getProperties().get(key);
                sb.append(space).append("\"").append(key).append("\": ");
                if (prop.get$ref() != null) prop = resolveRef(prop, openAPI);
                if ("object".equals(prop.getType())) {
                    sb.append(generateSchemaJson(prop, openAPI, indent + 1));
                } else if ("array".equals(prop.getType())) {
                    sb.append("[ ").append(generateFlatSchema(prop.getItems(), openAPI)).append(" ]");
                } else {
                    sb.append("\"").append(prop.getType()).append("\"");
                }
                sb.append(",\n");
            }
            sb.append(space).append("}");
        } else if ("array".equals(schema.getType())) {
            sb.append("[ ").append(generateSchemaJson(schema.getItems(), openAPI, indent + 1)).append(" ]");
        } else {
            sb.append("\"").append(schema.getType()).append("\"");
        }

        return sb.toString().replaceAll(",\n}", "\n}");
    }

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
