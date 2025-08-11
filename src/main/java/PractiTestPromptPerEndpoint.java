import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Generate one Copilot prompt file per endpoint.
 * - Hardcoded SWAGGER_FILE and OUTPUT_DIR (edit below).
 * - Java 8 compatible.
 */
public class PractiTestPromptPerEndpoint {

    // === Edit these two paths for your environment ===
    private static final String SWAGGER_FILE = "src/main/resources/openapi.yaml";
    private static final String OUTPUT_DIR = "generated-prompts_practitest";
    // =================================================

    private final OpenAPI openAPI;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    // collect guessed fields reasons per prompt run
    private final Map<String, String> guessedFields = new LinkedHashMap<>();

    public PractiTestPromptPerEndpoint(OpenAPI openAPI) {
        this.openAPI = openAPI;
    }

    public static void main(String[] args) throws Exception {
        // load swagger (hardcoded)
        OpenAPI openAPI = new OpenAPIV3Parser().read(SWAGGER_FILE);
        if (openAPI == null) {
            System.err.println("❌ Failed to load OpenAPI from: " + SWAGGER_FILE);
            return;
        }

        // create output dir
        File out = new File(OUTPUT_DIR);
        if (!out.exists()) {
            if (!out.mkdirs()) {
                System.err.println("❌ Could not create output directory: " + OUTPUT_DIR);
                return;
            }
        }

        PractiTestPromptPerEndpoint gen = new PractiTestPromptPerEndpoint(openAPI);
        gen.generateAllPrompts();

        System.out.println("✅ Prompts generated in: " + OUTPUT_DIR);
    }

    public void generateAllPrompts() throws IOException {
        if (openAPI.getPaths() == null) {
            System.out.println("No paths in OpenAPI.");
            return;
        }

        for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();
            Map<PathItem.HttpMethod, Operation> ops = pathItem.readOperationsMap();
            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : ops.entrySet()) {
                PathItem.HttpMethod method = opEntry.getKey();
                Operation operation = opEntry.getValue();

                guessedFields.clear(); // fresh for each prompt
                String prompt = buildPromptForOperation(path, method, operation);
                String safeName = makeSafeFileName(method.name() + "_" + path) + ".txt";
                File outFile = new File(OUTPUT_DIR, safeName);
                try (FileWriter fw = new FileWriter(outFile)) {
                    fw.write(prompt);
                }
            }
        }
    }

    private String buildPromptForOperation(String path, PathItem.HttpMethod method, Operation op) throws IOException {
        StringBuilder sb = new StringBuilder();

        sb.append("You are a professional QA engineer. GIVEN the API endpoint specification below, ")
                .append("OUTPUT ONLY (no prose) a single JSON array containing PractiTest test cases ready for bulk import.\n\n");

        sb.append("IMPORTANT REQUIREMENTS FOR COPILOT (must follow exactly):\n");
        sb.append("1) Return a single JSON array and nothing else (no markdown, no explanations).\n");
        sb.append("2) Each array element must be a PractiTest test case object suitable for bulk import and include at least:\n")
                .append("   - title (string)\n   - description (string)\n   - steps: an ordered array of {step_number, action, expected_result}\n")
                .append("   - priority (Low/Medium/High) and type (Functional/Regression/etc.)\n");
        sb.append("3) Use the provided `sample_request` / `sample_response` structure exactly as the basis. If you refine values that were inferred, add them to `guessed_fields` with a JSON-Pointer -> short reason.\n");
        sb.append("4) Produce at least (a) positive/nominal test case and (b) negative test case(s) (invalid payloads, missing required fields, type errors).\n");
        sb.append("5) For endpoints with oneOf/anyOf variants, produce one test case per variant.\n");
        sb.append("6) Include request headers/auth hints if applicable (e.g., Authorization: Bearer <token>).\n");
        sb.append("7) Keep example values realistic (emails, ISO dates, UUIDs, currency formats). Do not invent unrealistic formats.\n\n");

        // Endpoint metadata
        sb.append("Endpoint:\n");
        sb.append("  method: ").append(method).append("\n");
        sb.append("  path: ").append(path).append("\n");
        sb.append("  summary: ").append(nullToNA(op.getSummary())).append("\n");
        sb.append("  description: ").append(nullToNA(op.getDescription())).append("\n\n");

        // Parameters
        sb.append("Parameters:\n");
        List<Map<String,Object>> params = new ArrayList<>();
        if (op.getParameters() != null) {
            for (Parameter p : op.getParameters()) {
                Map<String,Object> pm = new LinkedHashMap<>();
                pm.put("name", p.getName());
                pm.put("in", p.getIn());
                pm.put("required", p.getRequired() == null ? false : p.getRequired());
                pm.put("description", p.getDescription());
                // sample value (try to infer)
                Object pv = exampleFromSchemaWithState(openAPI, p.getSchema(), new HashSet<String>(), "/parameters/" + p.getName());
                pm.put("example", pv);
                params.add(pm);
            }
        }
        sb.append(mapper().writeValueAsString(params)).append("\n\n");

        // Request body (handle oneOf/anyOf variations)
        sb.append("sample_request_variations:\n");
        List<Object> requestVariations = new ArrayList<>();
        if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
            MediaType jsonMt = selectJsonMediaType(op.getRequestBody().getContent());
            if (jsonMt != null && jsonMt.getSchema() != null) {
                Schema<?> bodySchema = jsonMt.getSchema();
                if ((bodySchema.getOneOf() != null && !bodySchema.getOneOf().isEmpty()) ||
                        (bodySchema.getAnyOf() != null && !bodySchema.getAnyOf().isEmpty())) {
                    List<Schema> choices = bodySchema.getOneOf() != null ? bodySchema.getOneOf() : bodySchema.getAnyOf();
                    int idx = 0;
                    for (Schema s : choices) {
                        Object example = exampleFromSchemaWithState(openAPI, s, new HashSet<String>(), "/requestBody/variant" + idx);
                        requestVariations.add(example);
                        idx++;
                    }
                } else {
                    Object example = exampleFromSchemaWithState(openAPI, bodySchema, new HashSet<String>(), "/requestBody");
                    requestVariations.add(example);
                }
            }
        } else {
            requestVariations.add(null);
        }
        sb.append(mapper().writeValueAsString(requestVariations)).append("\n\n");

        // Response example (prefer 200/201)
        sb.append("sample_response:\n");
        Object responseExample = null;
        ApiResponse success = first2xxResponse(op.getResponses());
        if (success != null && success.getContent() != null) {
            MediaType rmt = selectJsonMediaType(success.getContent());
            if (rmt != null && rmt.getSchema() != null) {
                responseExample = exampleFromSchemaWithState(openAPI, rmt.getSchema(), new HashSet<String>(), "/response");
            }
        }
        sb.append(mapper().writeValueAsString(responseExample)).append("\n\n");

        // Guessed fields map (help Copilot explain what was inferred)
        sb.append("guessed_fields:\n");
        sb.append(mapper().writeValueAsString(guessedFields)).append("\n\n");

        // Extra instructions about coverage and scenarios
        sb.append("Coverage instructions:\n");
        sb.append("- Include one nominal (happy path) test that asserts the primary success response and key fields.\n");
        sb.append("- Include at least two negative tests: missing required field and invalid type/value. Include expected error code and message.\n");
        sb.append("- If request/response include arrays, test empty array and array with multiple items.\n");
        sb.append("- If there are oneOf/anyOf variants, create separate tests for each variant and show which variant was used (discriminator or example fields).\n");
        sb.append("- Validate status codes, response JSON shape, and important business rules (e.g., totals, dates, string formats).\n");
        sb.append("- For endpoints requiring authentication, include a step to obtain a token and use it.\n\n");

        // Finally include a compact pre-filled JSON template (Copilot should use/expand it)
        Map<String,Object> template = new LinkedHashMap<>();
        template.put("title", op.getSummary() != null ? op.getSummary() : method + " " + path);
        template.put("description", op.getDescription() != null ? op.getDescription() : "");
        template.put("endpoint", method + " " + path);
        template.put("sample_request", requestVariations.size() == 1 ? requestVariations.get(0) : requestVariations);
        template.put("sample_response", responseExample);
        template.put("guessed_fields", guessedFields);
        template.put("steps", Arrays.asList(
                new LinkedHashMap<String,Object>() {{ put("step_number", 1); put("action", "Send request using sample_request"); put("expected_result", "Receive 2xx and body matches sample_response"); }},
                new LinkedHashMap<String,Object>() {{ put("step_number", 2); put("action", "Send invalid request (missing required)"); put("expected_result", "Receive 4xx and proper error message"); }}
        ));

        sb.append("Pre-filled PractiTest JSON template (use/expand — generate upload-ready PractiTest JSON array):\n");
        sb.append(mapper().writeValueAsString(template)).append("\n\n");

        sb.append("=== END OF SPEC ===\n\n");

        sb.append("REMEMBER: OUTPUT ONLY a single JSON array. Each element is a PractiTest test case object. No extra text.");

        // return as single prompt string
        return sb.toString();
    }

    // -------- helpers --------

    private ObjectMapper mapper() { return mapper; }

    private static String nullToNA(String s) { return s == null ? "" : s; }

    private static MediaType selectJsonMediaType(Content content) {
        if (content == null) return null;
        if (content.containsKey("application/json")) return content.get("application/json");
        // fallback to first
        for (MediaType m : content.values()) return m;
        return null;
    }

    private ApiResponse first2xxResponse(ApiResponses responses) {
        if (responses == null) return null;
        for (Map.Entry<String, ApiResponse> e : responses.entrySet()) {
            String code = e.getKey();
            if (code != null && code.startsWith("2")) return e.getValue();
        }
        return null;
    }

    private String makeSafeFileName(String input) {
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // Example generation with cycle detection and heuristics
    @SuppressWarnings("unchecked")
    private Object exampleFromSchemaWithState(OpenAPI openAPI, Schema schema, Set<String> visitedRefs, String pointer) {
        if (schema == null) return null;

        // explicit example or default or enum
        if (schema.getExample() != null) return schema.getExample();
        if (schema.getDefault() != null) return schema.getDefault();
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) return schema.getEnum().get(0);

        // $ref
        if (schema.get$ref() != null) {
            String ref = schema.get$ref();
            if (visitedRefs.contains(ref)) {
                guessedFields.put(pointer, "circular-ref");
                return null;
            }
            visitedRefs.add(ref);
            String refName = ref.replace("#/components/schemas/", "");
            if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
                Schema refSchema = openAPI.getComponents().getSchemas().get(refName);
                if (refSchema != null) {
                    Object val = exampleFromSchemaWithState(openAPI, refSchema, visitedRefs, pointer + "/" + refName);
                    visitedRefs.remove(ref);
                    return val;
                }
            }
            visitedRefs.remove(ref);
            guessedFields.put(pointer, "unresolved-ref");
            return null;
        }

        // allOf -> merge
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            Map<String,Object> merged = new LinkedHashMap<>();
            for (Object sObj : schema.getAllOf()) {
                Schema s = (Schema) sObj;
                Object ex = exampleFromSchemaWithState(openAPI, s, visitedRefs, pointer + "/allOf");
                if (ex instanceof Map) merged.putAll((Map)ex);
            }
            guessedFields.put(pointer, "allOf-merged");
            return merged;
        }

        // oneOf/anyOf -> pick first but caller may produce variations
        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            Schema first = (Schema) schema.getOneOf().get(0);
            guessedFields.put(pointer, "oneOf-picked-first");
            return exampleFromSchemaWithState(openAPI, first, visitedRefs, pointer + "/oneOf[0]");
        }
        if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
            Schema first = (Schema) schema.getAnyOf().get(0);
            guessedFields.put(pointer, "anyOf-picked-first");
            return exampleFromSchemaWithState(openAPI, first, visitedRefs, pointer + "/anyOf[0]");
        }

        // array
        if (schema instanceof ArraySchema || "array".equals(schema.getType())) {
            ArraySchema as = (schema instanceof ArraySchema) ? (ArraySchema) schema : null;
            Schema items = (as != null ? as.getItems() : null);
            if (items == null) {
                guessedFields.put(pointer, "array-no-items");
                return new ArrayList<Object>();
            }
            Object item = exampleFromSchemaWithState(openAPI, items, visitedRefs, pointer + "/0");
            List<Object> list = new ArrayList<Object>();
            list.add(item);
            guessedFields.put(pointer, "array-generated-1");
            return list;
        }

        // object
        if ("object".equals(schema.getType()) || schema.getProperties() != null) {
            Map<String,Object> obj = new LinkedHashMap<>();
            Map<String, Schema> props = schema.getProperties();
            if (props != null) {
                for (Map.Entry<String, Schema> e : props.entrySet()) {
                    String name = e.getKey();
                    Schema propSchema = e.getValue();
                    Object val = exampleFromSchemaWithState(openAPI, propSchema, visitedRefs, pointer + "/" + name);
                    val = applyNameHeuristics(name, val, propSchema);
                    obj.put(name, val);
                }
            }
            Object add = schema.getAdditionalProperties();
            if (add instanceof Schema) {
                Object val = exampleFromSchemaWithState(openAPI, (Schema) add, visitedRefs, pointer + "/additionalProp");
                obj.put("additionalProp1", val);
            }
            guessedFields.put(pointer, "object-generated");
            return obj;
        }

        // primitives
        String type = schema.getType();
        String fmt = schema.getFormat();
        if ("string".equals(type)) {
            if ("date-time".equals(fmt) || "date".equals(fmt)) {
                guessedFields.put(pointer, "generated-date");
                return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            }
            if ("uuid".equals(fmt) || pointer.toLowerCase().contains("id")) {
                guessedFields.put(pointer, "generated-uuid");
                return "11111111-1111-1111-1111-111111111111";
            }
            if (pointer.toLowerCase().contains("email")) {
                guessedFields.put(pointer, "generated-email");
                return "john.doe@example.com";
            }
            if (pointer.toLowerCase().contains("name")) {
                guessedFields.put(pointer, "generated-name");
                return "John Doe";
            }
            if (pointer.toLowerCase().contains("phone")) {
                guessedFields.put(pointer, "generated-phone");
                return "+1-555-555-0100";
            }
            guessedFields.put(pointer, "generated-string");
            return "sample text";
        }
        if ("integer".equals(type)) { guessedFields.put(pointer, "generated-integer"); return 42; }
        if ("number".equals(type))  { guessedFields.put(pointer, "generated-number");  return 199.99; }
        if ("boolean".equals(type)) { guessedFields.put(pointer, "generated-boolean"); return true; }

        guessedFields.put(pointer, "fallback");
        return null;
    }

    private Object applyNameHeuristics(String propName, Object currentValue, Schema propSchema) {
        if (!(currentValue instanceof String)) return currentValue;
        String lower = propName.toLowerCase();
        if (lower.contains("email")) return "john.doe@example.com";
        if (lower.contains("first") && lower.contains("name")) return "John";
        if (lower.contains("last") && lower.contains("name")) return "Doe";
        if (lower.contains("name")) return "John Doe";
        if (lower.contains("phone")) return "+1-555-555-0100";
        if (lower.contains("date")) return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        if (lower.contains("amount") || lower.contains("price") || lower.contains("total")) return 199.99;
        return currentValue;
    }
}
