
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.*;
import io.swagger.v3.oas.models.responses.*;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.io.*;
import java.nio.file.*;
import java.nio.file.Paths;
import java.util.*;

public class KaratePromptBuilder {

    private static final String OUTPUT_DIR = "generated-prompts";

    public static void main(String[] args) throws IOException {
        String inputYamlPath = "src/main/resources/openapi.yaml";
        SwaggerParseResult parseResult = new OpenAPIV3Parser().readLocation(inputYamlPath, null, null);
        OpenAPI openAPI = parseResult.getOpenAPI();

        if (openAPI == null || openAPI.getPaths() == null) {
            System.err.println("❌ Failed to parse OpenAPI YAML");
            return;
        }

        Map<String, Schema> schemaMap = Optional.ofNullable(openAPI.getComponents()).map(Components::getSchemas).orElse(Collections.emptyMap());

        for (Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet()) {
            String path = entry.getKey();
            PathItem pathItem = entry.getValue();

            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : pathItem.readOperationsMap().entrySet()) {
                PathItem.HttpMethod method = opEntry.getKey();
                Operation op = opEntry.getValue();

                String prompt = buildPrompt(openAPI, schemaMap, path, method.name(), op);
                savePromptToFile(path, method.name(), prompt);
            }
        }
    }

    private static String buildPrompt(OpenAPI openAPI, Map<String, Schema> schemaMap, String path, String method, Operation op) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate a Karate test scenario for this OpenAPI endpoint:\n\n")
                .append("Path: ").append(path).append("\n")
                .append("Method: ").append(method.toUpperCase()).append("\n")
                .append("Summary: ").append(op.getSummary()).append("\n\n");

        List<Parameter> params = op.getParameters() != null ? op.getParameters() : Collections.emptyList();
        if (!params.isEmpty()) {
            prompt.append("Parameters:\n");
            for (Parameter p : params) {
                Schema<?> schema = p.getSchema();
                prompt.append("- ").append(p.getName()).append(" (in: ").append(p.getIn())
                        .append(", required: ").append(p.getRequired())
                        .append(", type: ").append(schema != null ? schema.getType() : "unknown").append(")\n");
            }
        } else {
            prompt.append("Parameters: none\n");
        }

        // Request Body
        if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
            Content content = op.getRequestBody().getContent();
            for (Map.Entry<String, MediaType> mediaEntry : content.entrySet()) {
                String mediaTypeKey = mediaEntry.getKey();
                MediaType mediaType = mediaEntry.getValue();

                prompt.append("\n-- Media Type: ").append(mediaTypeKey).append(" --\n");

                if (mediaTypeKey.contains("multipart/form-data")) {
                    prompt.append("Handle as multipart upload with file and form fields\n");
                }

                if (mediaType != null && mediaType.getSchema() != null) {
                    prompt.append("Sample request body:\n");
                    appendSchemaFields(prompt, mediaType.getSchema(), schemaMap, 1);
                    if (mediaType.getExample() != null) {
                        prompt.append(indent(1)).append("Example: ").append(mediaType.getExample().toString()).append("\n");
                    } else if (mediaType.getExamples() != null) {
                        mediaType.getExamples().forEach((k, v) -> {
                            if (v.getValue() != null) {
                                prompt.append(indent(1)).append("Example - ").append(k).append(": ").append(v.getValue().toString()).append("\n");
                            }
                        });
                    }
                }
            }
        }

        // Responses
        ApiResponses responses = op.getResponses();
        if (responses != null) {
            for (Map.Entry<String, ApiResponse> responseEntry : responses.entrySet()) {
                String statusCode = responseEntry.getKey();
                ApiResponse response = responseEntry.getValue();

                prompt.append("\nSample ").append(statusCode).append(" response:\n");
                Content content = response.getContent();
                if (content != null) {
                    for (Map.Entry<String, MediaType> mediaEntry : content.entrySet()) {
                        String mediaTypeKey = mediaEntry.getKey();
                        MediaType mediaType = mediaEntry.getValue();
                        prompt.append(indent(1)).append("Content-Type: ").append(mediaTypeKey).append("\n");
                        if (mediaType.getSchema() != null) {
                            appendSchemaFields(prompt, mediaType.getSchema(), schemaMap, 1);
                            if (mediaType.getSchema().getExample() != null) {
                                prompt.append(indent(1)).append("Example: ").append(mediaType.getSchema().getExample().toString()).append("\n");
                            }
                        }
                    }
                } else {
                    prompt.append("- No schema defined\n");
                }
            }
        }

        prompt.append("\nInstructions:\n")
                .append("- Use Karate syntax\n")
                .append("- Include a scenario for successful response\n")
                .append("- Handle path/query/header parameters\n")
                .append("- Validate response fields\n")
                .append("- Add negative test cases for 400, 422 and other non-2xx responses\n")
                .append("- Cover oneOf/anyOf/allOf combinations with variations\n")
                .append("- Handle $ref and schemas with no properties\n")
                .append("- Expand tests for each combination of oneOf/anyOf branches\n")
                .append("- Split scenarios by media type where applicable\n")
                .append("- Special handling for multipart/form-data\n");

        return prompt.toString();
    }

    private static void appendSchemaFields(StringBuilder prompt, Schema<?> schema, Map<String, Schema> schemaMap, int indent) {
        if (schema == null) return;

        if (schema.get$ref() != null) {
            String ref = schema.get$ref().replace("#/components/schemas/", "");
            appendSchemaFields(prompt, schemaMap.get(ref), schemaMap, indent);
        } else if (schema.getAllOf() != null) {
            for (Schema<?> s : schema.getAllOf()) {
                appendSchemaFields(prompt, s, schemaMap, indent);
            }
        } else if (schema.getOneOf() != null) {
            prompt.append(indent(indent)).append("oneOf:\n");
            for (Schema<?> s : schema.getOneOf()) {
                appendSchemaFields(prompt, s, schemaMap, indent + 1);
            }
        } else if (schema.getAnyOf() != null) {
            prompt.append(indent(indent)).append("anyOf:\n");
            for (Schema<?> s : schema.getAnyOf()) {
                appendSchemaFields(prompt, s, schemaMap, indent + 1);
            }
        } else if (schema.getDiscriminator() != null) {
            prompt.append(indent(indent)).append("Discriminator: ").append(schema.getDiscriminator().getPropertyName()).append("\n");
        } else if (schema.getAdditionalProperties() instanceof Schema) {
            prompt.append(indent(indent)).append("Map-like object:\n");
            appendSchemaFields(prompt, (Schema<?>) schema.getAdditionalProperties(), schemaMap, indent + 1);
        } else if (schema.getProperties() != null) {
            for (Map.Entry<String, Schema> entry : (Set<Map.Entry<String, Schema>>) schema.getProperties().entrySet()) {
                String key = entry.getKey();
                Schema<?> prop = entry.getValue();
                prompt.append(indent(indent)).append("- ").append(key)
                        .append(" (type: ").append(prop.getType() != null ? prop.getType() : "object");
                if (prop.getFormat() != null) prompt.append(", format: ").append(prop.getFormat());
                if (prop.getEnum() != null) prompt.append(", enum: ").append(prop.getEnum());
                if (prop.getDefault() != null) prompt.append(", default: ").append(prop.getDefault());
                prompt.append(")\n");
                appendSchemaFields(prompt, prop, schemaMap, indent + 1);
            }
        } else {
            prompt.append(indent(indent)).append("- unknown schema\n");
        }
    }

    private static String indent(int level) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }

    private static void savePromptToFile(String path, String method, String prompt) throws IOException {
        String safeName = (method + "_" + path.replaceAll("[/{}/]", "_")).replaceAll("_+", "_") + ".txt";
        Path output = Paths.get(OUTPUT_DIR, safeName);
        Files.createDirectories(output.getParent());
        Files.write(output, prompt.getBytes());
        System.out.println("✅ Saved: " + output);
    }
}
