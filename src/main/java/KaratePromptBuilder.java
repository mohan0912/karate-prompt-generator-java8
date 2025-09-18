import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.*;
import io.swagger.v3.oas.models.responses.*;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.io.*;
import java.nio.file.*;
import java.nio.file.Paths;

import java.util.*;

public class KaratePromptBuilder {

    private static final String OUTPUT_DIR = "generated-prompts1";
    private static final String inputYamlPath = "src/main/resources/openapi.yaml";
    private static final boolean INCLUDE_GLOBAL_RESPONSES = true;

    public static void main(String[] args) throws IOException {

        SwaggerParseResult parseResult = new OpenAPIV3Parser().readLocation(inputYamlPath, null, null);
        OpenAPI openAPI = parseResult.getOpenAPI();

        if (openAPI == null || openAPI.getPaths() == null) {
            System.err.println("❌ Failed to parse OpenAPI YAML: " + parseResult.getMessages());
            return;
        }

        Map<String, Schema> schemaMap = Optional.ofNullable(openAPI.getComponents()).map(Components::getSchemas).orElse(Collections.emptyMap());

        for (Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet()) {
            String path = entry.getKey();
            PathItem pathItem = entry.getValue();

            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : pathItem.readOperationsMap().entrySet()) {
                PathItem.HttpMethod method = opEntry.getKey();
                Operation op = opEntry.getValue();

                try {
                    String prompt = buildPrompt(openAPI, schemaMap, path, method.name(), op);
                    savePromptToFile(path, method.name(), prompt);
                } catch (Exception e) {
                    System.err.println("⚠ Error building prompt for " + method + " " + path + ": " + e.getMessage());
                }
            }
        }
    }

    private static String repeat(String str, int count) {
        StringBuilder sb = new StringBuilder(str.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    private static String buildPrompt(OpenAPI openAPI, Map<String, Schema> schemaMap,
                                      String path, String method, Operation op) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate all possible Karate test scenario for this OpenAPI endpoint \n\n")
                .append("Path: ").append(path).append("\n")
                .append("Method: ").append(method.toUpperCase()).append("\n")
                .append("Summary: ").append(op.getSummary() != null ? op.getSummary() : "(no summary provided)").append("\n\n");

        List<Parameter> params = new ArrayList<>();
        PathItem pathItem = openAPI.getPaths().get(path);

        if (pathItem.getParameters() != null) {
            for (Parameter p : pathItem.getParameters()) {
                params.add(resolveParameter(openAPI, p));
            }
        }

        if (op.getParameters() != null) {
            for (Parameter p : op.getParameters()) {
                params.add(resolveParameter(openAPI, p));
            }
        }

        if (!params.isEmpty()) {
            prompt.append("Parameters:/n");
            for (Parameter p : params) {
                Schema<?> schema = p.getSchema();
                prompt.append("- ").append(p.getName()).append(" (in: ").append(p.getIn())
                        .append(", required: ").append(p.getRequired())
                        .append(", type: ").append(schema != null ? schema.getType() : "unknown").append(")\n");
            }
        } else {
            prompt.append("Parameters: none\n");
        }

        if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
            Content content = op.getRequestBody().getContent();
            for (Map.Entry<String, MediaType> mediaEntry : content.entrySet()) {
                String mediaTypeKey = mediaEntry.getKey();
                MediaType mediaType = mediaEntry.getValue();

                prompt.append("\n-- Media Type: ").append(mediaTypeKey).append(" --\n");

                if (mediaTypeKey.contains("multipart/form-data")) {
                    prompt.append("jHandle as multipart upload with file and form fields\n");
                }

                if (mediaType != null && mediaType.getSchema() != null) {
                    prompt.append("Sample request body:\n");
                    appendSchemaFields(prompt, mediaType.getSchema(), schemaMap, 1);
                    if (mediaType.getExample() != null) {
                        prompt.append(indent(1)).append("Example: ").append(mediaType.getExample().toString()).append("\n");
                    } else if (mediaType.getExamples() != null) {
                        mediaType.getExamples().forEach((k, v) -> {
                            if (v.getValue() != null) {
                                prompt.append(indent(1)).append("Example - ").append(k).append(": ")
                                        .append(v.getValue().toString()).append("\n");
                            }
                        });
                    }
                }
            }
        }

        ApiResponses responses = op.getResponses();
        Set<String> presentCode = responses != null ? responses.keySet() : new HashSet<>();

        if (INCLUDE_GLOBAL_RESPONSES && openAPI.getComponents() != null && openAPI.getComponents().getResponses() != null) {
            for (Map.Entry<String, ApiResponse> globalEntry : openAPI.getComponents().getResponses().entrySet()) {
                String code = globalEntry.getKey();
                if (!presentCode.contains(code)) {
                    if (responses == null) responses = new ApiResponses();
                    responses.addApiResponse(code, globalEntry.getValue());
                }
            }
        }


        if (responses != null) {
            for (Map.Entry<String, ApiResponse> responseEntry : responses.entrySet()) {
                String statuscode = responseEntry.getKey();
                ApiResponse response = responseEntry.getValue();
                if (response.get$ref() != null) {
                    String ref = response.get$ref().replace("#/components/responses", "");
                    response = openAPI.getComponents().getResponses().get(ref);
                }

                prompt.append("\nSample :").append(statuscode).append(" responses:\n");
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
                    prompt.append(" - No Schema defined \n");
                }
            }
        }


        prompt.append("\nInstructions:\n")
                .append(" Use Karate DSL syntax\n")
                .append("- Include a scenario for successful response\n")
                .append("- Handle path/query/header parameters\n")
                .append("- Validate response fields\n")
                .append("- Add negative test cases for 400, 422 and other non-2xx responses\n")
                .append("- Cover one0f/any0f/allof combinations with variations\n")
                .append("- Handle $ref and schemas with no properties\n")
                .append("- Expand tests for each combination of one0f/anyof branches\n")
                .append("- Split scenarios by media type where applicable\n")
                .append("- Special handling for multipart/form-data\n")
                .append("- validate each fields in request for pattern, length, and invalid values (if there is any)\n")
                .append("- validate the reposne schema for pattern, length, and invalid values (if there is any)\n")
                .append("- validate the response body fields value for 2xx responses (if there is any)\n")
                .append("- Add scenarios for each expected error code: 4xx, 5xx, etc.\n")
                .append("- Test edge cases: empty arrays, nulls, boundary values\n")
                .append("- Validate business logic and domain-specific rules\n")
                .append("- Include request and response examples in scenarios\n")
                .append("- Validate enum values and required/optional fields\n");


        return prompt.toString();

    }

    private static Parameter resolveParameter(OpenAPI openAPI, Parameter p) {
        if (p.get$ref() != null) {
            String ref = p.get$ref();
            String name = ref.substring(ref.lastIndexOf('/' + 1));
            return openAPI.getComponents().getParameters().get(name);
        }
        return p;
    }

    private static void appendSchemaFields(StringBuilder prompt, Schema<?> schema, Map<String, Schema> schemaMap, int indent) {
        if (schema == null) return;

        if (schema.get$ref() != null) {
            String ref = schema.get$ref().replace("#/components/schemas/", "");
            Schema<?> refSchema = schemaMap.get(ref);
            if (refSchema != null) {
                appendSchemaFields(prompt, refSchema, schemaMap, indent);
            } else {
                prompt.append(indent(indent)).append("- unknown schema (unresolved ref: ").append(ref).append(")\n");
            }
            return;
        }

        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            prompt.append(indent(indent)).append("allof:\n");
            for (Schema<?> s : schema.getAllOf()) {
                appendSchemaFields(prompt, s, schemaMap, indent + 1);
            }
            return;
        }

        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            prompt.append(indent(indent)).append("oneof:\n");
            for (Schema<?> s : schema.getOneOf()) {
                appendSchemaFields(prompt, s, schemaMap, indent + 1);
            }
            return;
        }

        if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
            prompt.append(indent(indent)).append("any0f:\n");
            for (Schema<?> s : schema.getAnyOf()) {
                appendSchemaFields(prompt, s, schemaMap, indent + 1);
            }
            return;
        }

// Handle array

        if ("array" .equals(schema.getType()) || schema instanceof ArraySchema) {
            Schema<?> items = schema instanceof ArraySchema ? ((ArraySchema) schema).getItems() : schema.getItems();
            prompt.append(indent(indent)).append("- array\n");
            if (items != null) {
                prompt.append(indent(indent + 1)).append("items:\n");
                appendSchemaFields(prompt, items, schemaMap, indent + 2);
            } else {
                prompt.append(indent(indent + 1)).append(" unknown items schema\n");
            }
            return;
        }

// Handle object

        if ("object" .equals(schema.getType()) || schema.getProperties() != null) {
            Map<String, Schema> props = schema.getProperties();
            if (props != null && !props.isEmpty()) {
                for (Map.Entry<String, Schema> entry : props.entrySet()) {
                    String key = entry.getKey();
                    Schema<?> prop = entry.getValue();
                    String type = prop.getType() != null ? prop.getType() : "object";
                    prompt.append(indent(indent)).append("- ").append(key)
                            .append(" (type: ").append(type);

                    if (prop.getFormat() != null) prompt.append(", format: ").append(prop.getFormat());
                    if (prop.getEnum() != null) prompt.append(", enum: ").append(prop.getEnum());
                    if (prop.getDefault() != null) prompt.append(", default: ").append(prop.getDefault());
                    if (prop.getPattern() != null) prompt.append(", pattern: ").append(prop.getPattern());
                    if (prop.getMinLength() != null) prompt.append(", minlength: ").append(prop.getMinLength());
                    if (prop.getMaxLength() != null) prompt.append(", maxLength: ").append(prop.getMaxLength());
                    prompt.append("\n");
                    appendSchemaFields(prompt, prop, schemaMap, indent + 1);
                }

            } else {
                prompt.append(indent(indent)).append("-object (no properties)\n");


            }
            return;
        }
        String type = schema.getType();
        if (type != null) {
            prompt.append(indent(indent)).append("- ").append(type);

            if (schema.getFormat() != null) prompt.append(", format: ").append(schema.getFormat()).append(")");
            if (schema.getEnum() != null) prompt.append(", enum: ").append(schema.getEnum()).append(")");
            if (schema.getPattern() != null) prompt.append(", pattern: ").append(schema.getPattern()).append(")");
            if (schema.getMinLength() != null) prompt.append(", minlength: ").append(schema.getMinLength()).append(")");
            if (schema.getMaxLength() != null) prompt.append(", maxLength: ").append(schema.getMaxLength()).append(")");
            prompt.append("\n");
        } else {
            prompt.append(indent(indent)).append("- unknown Schema");
        }
    }

    private static String indent(int level) {
        StringBuilder sb = new StringBuilder(level * 2);
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
