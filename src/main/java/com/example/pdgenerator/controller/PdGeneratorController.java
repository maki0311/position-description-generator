package com.example.pdgenerator.controller;

import com.example.pdgenerator.request.PdRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.pdgenerator.service.PdService;
import com.example.pdgenerator.jobseries.JobSeriesService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.Locale;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * REST controller responsible for handling requests related to
 * position description generation using OpenAI API.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class PdGeneratorController {

    @Value("${usajobs.api.key}")
    private String usajobsApiKey;

    @Value("${usajobs.user.agent}")
    private String usajobsUserAgent;

    private final PdService pdService;
    private final JobSeriesService jobSeriesService;

    /**
     * Constructor injection for the services.
     */
    public PdGeneratorController(PdService pdService, JobSeriesService jobSeriesService) {
        this.pdService = pdService;
        this.jobSeriesService = jobSeriesService;
        // Fetch and process job series data at startup, but do not fail app if it errors
        try {
            this.jobSeriesService.fetchAndProcessJobSeries();
        } catch (Exception e) {
            System.err.println("[Startup] Skipping USAJOBS bootstrap (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Inner class representing the JSON structure of the request
     * sent to the OpenAI API backend.
     */
    class OpenAIRequest {
        private String model;
        private List<Message> messages;
        private boolean stream;
        private int max_tokens; // <-- update to match OpenAI API
        private double temperature;

        public OpenAIRequest(String model, List<Message> messages, boolean stream) {
            this.model = model;
            this.messages = messages;
            this.stream = stream;
            this.max_tokens = 8000; // <-- update field name
            this.temperature = 0.3;
        }

        // Getters and setters
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public List<Message> getMessages() { return messages; }
        public void setMessages(List<Message> messages) { this.messages = messages; }
        public boolean isStream() { return stream; }
        public void setStream(boolean stream) { this.stream = stream; }
        public int getMax_tokens() { return max_tokens; } // <-- update getter name
        public void setMax_tokens(int max_tokens) { this.max_tokens = max_tokens; } // <-- update setter name
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
    }

    class Message {
        private String role;
        private String content;
        
        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
        
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    private String getAutoPdfContext() {
    StringBuilder context = new StringBuilder();
    
    // CRITICAL: Load PDFs with STRICT size limits to avoid token overflow
    String opmStandards = loadPdfFromClasspathWithLimit("/static/pdfs/occupationalhandbook.pdf", 600);
    String factorGuide = loadPdfFromClasspathWithLimit("/static/pdfs/gsadmn.pdf", 400);
    String gradeGuide = loadPdfFromClasspathWithLimit("/static/pdfs/gssg.pdf", 300);
    String csGuide = loadPdfFromClasspathWithLimit("/static/pdfs/interpretive-guidance-for-cybersecurity-positions.pdf", 200);

    if (!opmStandards.isEmpty()) {
        context.append("OPM CLASSIFICATION STANDARDS (Summary):\n");
        context.append(opmStandards).append("\n\n");
    }
    if (!factorGuide.isEmpty()) {
        context.append("FACTOR EVALUATION SYSTEM (Key Points):\n");
        context.append(factorGuide).append("\n\n");
    }
    if (!gradeGuide.isEmpty()) {
        context.append("SUPERVISORY GUIDE (Summary):\n");
        context.append(gradeGuide).append("\n\n");
    }
    if (!csGuide.isEmpty()) {
        context.append("CYBERSECURITY GUIDE (Summary):\n");
        context.append(csGuide).append("\n\n");
    }
    
    // Log the actual size
    System.out.println("PDF context size: " + context.length() + " characters (~" + (context.length() / 4) + " tokens)");
    
    return context.toString();
}

private String loadPdfFromClasspathWithLimit(String classpathResource, int maxTokens) {
    try (InputStream is = getClass().getResourceAsStream(classpathResource)) {
        if (is == null) {
            System.out.println("PDF not found: " + classpathResource);
            return "";
        }

        byte[] pdfBytes = is.readAllBytes();
        PDDocument document = Loader.loadPDF(pdfBytes);
        PDFTextStripper stripper = new PDFTextStripper();
        
        // Only extract first few pages to limit size
        int totalPages = document.getNumberOfPages();
        stripper.setStartPage(1);
        stripper.setEndPage(Math.min(3, totalPages)); // Max 3 pages
        
        String text = stripper.getText(document);
        document.close();
        
        // Aggressive truncation: 1 token ≈ 4 characters
        int maxChars = maxTokens * 4;
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars);
            // Try to cut at sentence boundary
            int lastPeriod = text.lastIndexOf(". ");
            if (lastPeriod > maxChars * 0.7) {
                text = text.substring(0, lastPeriod + 1);
            }
            text += "\n[Truncated for size]";
        }
        
        System.out.println("Loaded PDF: " + classpathResource + " (" + text.length() + " chars, ~" + (text.length() / 4) + " tokens)");
        return text;
        
    } catch (Exception e) {
        System.err.println("Failed to load PDF " + classpathResource + ": " + e.getMessage());
        return "";
    }
}

    /**
     * POST endpoint to generate a position description (PD) based on
     * input parameters. Enhanced error handling and debugging.
     */
    @PostMapping("/generate")
public void generatePd(@RequestBody PdRequest request, HttpServletResponse response) throws Exception {
    System.out.println("=== PD GENERATION STARTED ===");

    System.out.println("[PD GENERATION] Incoming request variables:");
    System.out.println("  jobSeries: " + request.getJobSeries());
    System.out.println("  subJobSeries: " + request.getSubJobSeries());
    System.out.println("  positionTitle: " + request.getPositionTitle());
    System.out.println("  federalAgency: " + request.getFederalAgency());
    System.out.println("  subOrganization: " + request.getSubOrganization());
    System.out.println("  lowestOrg: " + request.getLowestOrg());
    System.out.println("  gsGrade: " + request.getGsGrade());
    System.out.println("  supervisoryLevel: " + request.getSupervisoryLevel());
    System.out.println("  totalPoints: " + request.getTotalPoints());
    System.out.println("  gradeRange: " + request.getGradeRange());
    System.out.println("  factorLevels: " + request.getFactorLevels());
    System.out.println("  factorPoints: " + request.getFactorPoints());
    System.out.println("  historicalData: " + (request.getHistoricalData() != null ? request.getHistoricalData().substring(0, Math.min(100, request.getHistoricalData().length())) + (request.getHistoricalData().length() > 100 ? "..." : "") : "null"));
    
    response.setContentType("text/event-stream");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-cache");
    response.setHeader("Connection", "keep-alive");
    response.setHeader("Access-Control-Allow-Origin", "*");

    PrintWriter writer = response.getWriter();

    try {
        // Validation
        if (request == null || request.getHistoricalData() == null || request.getHistoricalData().trim().isEmpty()) {
            writer.println("data: {\"error\":\"Job duties are required\"}\n");
            writer.flush();
            return;
        }

        String apiKey = pdService.getOpenaiApiKey();
        if (apiKey == null || !apiKey.startsWith("sk-")) {
            writer.println("data: {\"error\":\"Invalid API key\"}\n");
            writer.flush();
            return;
        }

        writer.println("data: {\"status\":\"Generating position description...\"}\n");
        writer.flush();

        // Build prompt - this should now use PdfProcessingService
        String prompt = pdService.buildPrompt(request);
        
        // CRITICAL: Validate token count BEFORE sending
        int estimatedInputTokens = prompt.length() / 4;
        System.out.println("Prompt length: " + prompt.length() + " chars (~" + estimatedInputTokens + " tokens)");
        
        // If prompt is too large, truncate it
        int maxInputTokens = 3000; // Leave room for 4000 output tokens
        if (estimatedInputTokens > maxInputTokens) {
            int maxChars = maxInputTokens * 4;
            System.out.println("WARNING: Truncating prompt from " + prompt.length() + " to " + maxChars + " chars");
            prompt = prompt.substring(0, maxChars);
            estimatedInputTokens = maxInputTokens;
        }

        // Prepare OpenAI request with SAFE token allocation
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", 
            "You are an expert federal HR classification specialist. " +
            "Create COMPLETE position descriptions with ALL sections fully written. " +
            "NO placeholders or brackets."
        ));
        messages.add(new Message("user", prompt));

        // Calculate safe output tokens
        int safeOutputTokens = Math.min(4000, 8000 - estimatedInputTokens - 200);
        
        OpenAIRequest openaiRequest = new OpenAIRequest("gpt-4", messages, true);
        openaiRequest.setMax_tokens(safeOutputTokens);
        openaiRequest.setTemperature(0.3);
        
        System.out.println("Token allocation: Input=" + estimatedInputTokens + ", Output=" + safeOutputTokens);
        
        ObjectMapper objectMapper = new ObjectMapper();
        String requestBody = objectMapper.writeValueAsString(openaiRequest);

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(java.time.Duration.ofSeconds(180))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();
            
        HttpResponse<InputStream> openaiResponse = client.send(httpRequest, 
            HttpResponse.BodyHandlers.ofInputStream());

        System.out.println("OpenAI response status: " + openaiResponse.statusCode());

        if (openaiResponse.statusCode() != 200) {
            String errorResponse = new BufferedReader(new InputStreamReader(openaiResponse.body()))
                .lines().collect(Collectors.joining("\n"));
            System.err.println("OpenAI Error: " + errorResponse);
            writer.println("data: {\"error\":\"API Error: " + escapeJson(errorResponse) + "\"}\n");
            writer.flush();
            return;
        }

        // Stream response
        StringBuilder fullText = new StringBuilder();
        int chunkCount = 0;
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(openaiResponse.body()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6);
                    if (!"[DONE]".equals(data.trim()) && !data.trim().isEmpty()) {
                        try {
                            JsonNode jsonNode = objectMapper.readTree(data);
                            if (jsonNode.has("choices") && jsonNode.get("choices").size() > 0) {
                                JsonNode choice = jsonNode.get("choices").get(0);
                                if (choice.has("delta") && choice.get("delta").has("content")) {
                                    String content = choice.get("delta").get("content").asText();
                                    if (!content.trim().isEmpty()) {
                                        writer.println("data: {\"response\":\"" + escapeJson(content) + "\"}\n");
                                        writer.flush();
                                        fullText.append(content);
                                        chunkCount++;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("Error parsing chunk: " + e.getMessage());
                        }
                    }
                }
            }
        }

        System.out.println("Streamed " + chunkCount + " chunks, total " + fullText.length() + " chars");

        if (fullText.length() == 0) {
            writer.println("data: {\"error\":\"No content generated. Please try again.\"}\n");
        } else {
            String formattedPD = fixPDFormatting(fullText.toString());
            writer.println("data: {\"fullPD\":\"" + escapeJson(formattedPD) + "\"}\n");
        }
        
        writer.println("data: [DONE]\n");
        writer.flush();
        
        System.out.println("PD generation completed");
        
    } catch (Exception e) {
        System.err.println("EXCEPTION in generatePd: " + e.getMessage());
        e.printStackTrace();
        writer.println("data: {\"error\":\"" + escapeJson(e.getMessage()) + "\"}\n");
    } finally {
        try {
            writer.flush();
            writer.close();
        } catch (Exception e) {
            System.err.println("Error closing writer: " + e.getMessage());
        }
    }
}

    @PostMapping("/generate-sync")
public ResponseEntity<Map<String, String>> generatePdSync(@RequestBody PdRequest request) throws Exception {
    System.out.println("=== PD GENERATION (NON-STREAMING) STARTED ===");

    Map<String, String> result = new HashMap<>();

    try {
        // Validation
        if (request == null || request.getHistoricalData() == null || request.getHistoricalData().trim().isEmpty()) {
            result.put("error", "Job duties are required");
            return ResponseEntity.badRequest().body(result);
        }

        String apiKey = pdService.getOpenaiApiKey();
        if (apiKey == null || !apiKey.startsWith("sk-")) {
            result.put("error", "Invalid API key");
            return ResponseEntity.badRequest().body(result);
        }

        // --- Ensure factor values are locked and valid ---
        String gsGrade = request.getGsGrade() != null ? request.getGsGrade() : "GS-13";
        String supervisoryLevel = request.getSupervisoryLevel() != null ? request.getSupervisoryLevel() : "Non-Supervisory";

        Map<String, String> factorLevels = request.getFactorLevels();
        Map<String, Integer> factorPoints = request.getFactorPoints();

        if (factorLevels == null || factorPoints == null || factorLevels.size() != 9 || factorPoints.size() != 9) {
            // Auto-generate locked factor levels and points for the assigned grade
            factorLevels = pdService.getDefaultFactorLevelsForGrade(gsGrade, supervisoryLevel);
            factorPoints = new HashMap<>();
            for (Map.Entry<String, String> entry : factorLevels.entrySet()) {
                factorPoints.put(entry.getKey(), pdService.getPointsForFactorLevel(entry.getKey(), entry.getValue()));
            }
            request.setFactorLevels(factorLevels);
            request.setFactorPoints(factorPoints);
        }

        // Calculate total points and grade range
        int totalPoints = factorPoints.values().stream().mapToInt(Integer::intValue).sum();
        String gradeRange = pdService.getPointRangeForGrade(gsGrade);

        request.setTotalPoints(totalPoints);
        request.setGradeRange(gradeRange);
        request.setGsGrade(gsGrade);

        String prompt = pdService.buildConcisePrompt(request);

        // Estimate token usage
        int promptTokens = prompt.length() / 4;
        int maxModelTokens = 8192;
        int safeOutputTokens = Math.min(1200, maxModelTokens - promptTokens - 200); // Increased for full PD

        System.out.println("Prompt length: " + prompt.length() + " chars (~" + promptTokens + " tokens)");
        System.out.println("Token allocation: Input=" + promptTokens + ", Output=" + safeOutputTokens);
        System.out.println("Received jobSeries: " + request.getJobSeries());
        System.out.println("Received positionTitle: " + request.getPositionTitle());
        System.out.println("Received subJobSeries: " + request.getSubJobSeries());

        // Call OpenAI synchronously (no streaming), passing historicalData as context
        try {
            // Pass safeOutputTokens to PdService if needed, or set it inside PdService
            String rawPD = pdService.callOpenAI(prompt, request.getHistoricalData());
            String formattedPD = pdService.fixPDFormatting(rawPD);
            result.put("fullPD", formattedPD);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            System.err.println("OpenAI error: " + e.getMessage());
            result.put("error", "OpenAI error: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }

    } catch (Exception e) {
        System.err.println("Exception in generatePdSync: " + e.getMessage());
        result.put("error", e.getMessage());
        return ResponseEntity.status(500).body(result);
    }
}
    
    /**
     * Enhanced OpenAI connection test with better error reporting
     */
    private boolean testOpenAIConnection() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();

            // Simple test request to OpenAI
            List<Message> testMessages = new ArrayList<>();
            testMessages.add(new Message("user", "Test"));

            OpenAIRequest testRequest = new OpenAIRequest("gpt-3.5-turbo", testMessages, false);
            testRequest.setMax_tokens(5); // <-- update method name
            testRequest.setTemperature(0.1);

            ObjectMapper objectMapper = new ObjectMapper();
            String requestBody = objectMapper.writeValueAsString(testRequest);
                    
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                    .header("Authorization", "Bearer " + pdService.getOpenaiApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            System.out.println("OpenAI connection test - Status: " + response.statusCode());
            
            if (response.statusCode() == 401) {
                System.err.println("OpenAI API Key is invalid or expired");
                return false;
            } else if (response.statusCode() == 429) {
                System.err.println("OpenAI API rate limit exceeded");
                return false;
            } else if (response.statusCode() != 200) {
                System.err.println("OpenAI API returned error: " + response.body());
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            System.err.println("OpenAI connection test failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Enhanced debugging endpoint to test prompts and API connection
     */
    @PostMapping("/test-prompt")
    public ResponseEntity<Map<String, Object>> testPrompt(@RequestBody PdRequest request) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            System.out.println("=== PROMPT TEST STARTED ===");
            
            // Test API key first
            String apiKey = pdService.getOpenaiApiKey();
            result.put("hasApiKey", apiKey != null && !apiKey.trim().isEmpty());
            result.put("apiKeyValid", apiKey != null && apiKey.startsWith("sk-"));
            
            if (request == null) {
                result.put("status", "error");
                result.put("message", "Request is null");
                return ResponseEntity.badRequest().body(result);
            }
            
            // Test request validation
            result.put("hasHistoricalData", request.getHistoricalData() != null);
            result.put("historicalDataEmpty", request.getHistoricalData() == null || 
                    request.getHistoricalData().trim().isEmpty());
            
            if (request.getHistoricalData() == null || request.getHistoricalData().trim().isEmpty()) {
                result.put("status", "error");
                result.put("message", "Historical data is required");
                return ResponseEntity.badRequest().body(result);
            }
            
            // Test prompt building
            String prompt = pdService.buildPrompt(request);
            result.put("status", "success");
            result.put("promptLength", prompt.length());
            result.put("promptPreview", prompt.substring(0, Math.min(500, prompt.length())));
            
            // Check for common issues
            List<String> warnings = new ArrayList<>();
            if (prompt.contains("[") || prompt.contains("XXX") || prompt.contains("placeholder")) {
                warnings.add("Prompt may contain placeholders");
            }
            if (prompt.length() > 4000) {
                warnings.add("Prompt is very long - may be truncated");
            }
            
            result.put("warnings", warnings);
            
            // Test OpenAI connection
            try {
                boolean connectionOk = testOpenAIConnection();
                result.put("openaiConnectionOk", connectionOk);
            } catch (Exception e) {
                result.put("openaiConnectionOk", false);
                result.put("connectionError", e.getMessage());
            }
            
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
            result.put("stackTrace", java.util.Arrays.toString(e.getStackTrace()));
        }
        
        return ResponseEntity.ok(result);
    }

    /**
     * Updated POST endpoint to rewrite duties/responsibilities using OpenAI API with streaming.
     */
    @PostMapping("/rewrite-duties")
    public void rewriteDutiesStreaming(@RequestBody Map<String, String> body, HttpServletResponse response) throws Exception {
        String duties = body.getOrDefault("duties", "");
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        PrintWriter writer = response.getWriter();

        if (duties.isBlank()) {
            writer.write("data: {\"error\":\"No duties provided.\"}\n\n");
            writer.flush();
            return;
        }

        try {
            String prompt = buildOptimizedRewritePrompt(duties);

            List<Message> messages = new ArrayList<>();
            messages.add(new Message("system", "You are a federal HR specialist expert in writing professional job descriptions."));
            messages.add(new Message("user", prompt));

            OpenAIRequest openaiRequest = new OpenAIRequest("gpt-4", messages, true);
            openaiRequest.setMax_tokens(1500);

            ObjectMapper objectMapper = new ObjectMapper();
            String requestBody = objectMapper.writeValueAsString(openaiRequest);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                    .header("Authorization", "Bearer " + pdService.getOpenaiApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<InputStream> openaiResponse = client.send(httpRequest,
                HttpResponse.BodyHandlers.ofInputStream());

            if (openaiResponse.statusCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(openaiResponse.body()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            if (!"[DONE]".equals(data.trim())) {
                                try {
                                    JsonNode jsonNode = objectMapper.readTree(data);
                                    if (jsonNode.has("choices") && jsonNode.get("choices").size() > 0) {
                                        JsonNode choice = jsonNode.get("choices").get(0);
                                        if (choice.has("delta") && choice.get("delta").has("content")) {
                                            String content = choice.get("delta").get("content").asText();
                                            writer.println("data: {\"rewritten\":\"" + escapeJson(content) + "\"}\n");
                                            writer.flush();
                                        }
                                    }
                                } catch (Exception e) {
                                    // Skip invalid JSON lines
                                }
                            }
                        }
                    }
                }
                writer.println("data: [DONE]\n");
            } else {
                writer.write("data: {\"error\":\"OpenAI API returned status " + openaiResponse.statusCode() + "\"}\n\n");
            }
        } catch (Exception e) {
            writer.write("data: {\"error\":\"" + escapeJson(e.getMessage()) + "\"}\n\n");
        } finally {
            writer.flush();
            writer.close();
        }
    }

    /**
     * Alternative non-streaming version with OpenAI API
     */
    @PostMapping("/rewrite-duties-sync")
    public Map<String, String> rewriteDutiesOptimized(@RequestBody Map<String, String> body) throws Exception {
        String duties = body.getOrDefault("duties", "");
        if (duties.isBlank()) {
            return Map.of("rewritten", "No duties provided.", "error", "true");
        }

        // Truncate if too long
        if (duties.length() > 2000) {
            duties = duties.substring(0, 2000) + "...";
        }

        try {
            String prompt = buildOptimizedRewritePrompt(duties);
            String rewritten = callOpenAIWithTimeout(prompt, 60);
            
            return Map.of("rewritten", rewritten.trim());
        } catch (Exception e) {
            return Map.of("rewritten", "Error: " + e.getMessage(), "error", "true");
        }
    }

    /**
     * Optimized prompt for rewriting duties
     */
    private String buildOptimizedRewritePrompt(String duties) {
        return String.format("""
            Use the following links as guidelines and references: https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/
            Use this as a reference and guide: https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/
            Use this as a reference and guide: https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/factor-evaluation-system/

            Duties may be listed with percentages. Duties with higher percentages must be given greater weight and considered more important in your analysis. Percentages if present depict the importance of that duty.

            Rewrite the following federal job duties to be more professional and clear.
            Keep the same meaning, but improve the language.
            Return ONLY the improved duties as a numbered or bulleted list.
            DO NOT include any explanation, reasoning, commentary, or thinking steps.
            DO NOT repeat the original duties.
            DO NOT include any text except the rewritten duties list.
            
            %s
            """, duties);
    }

    /**
     * Call OpenAI API with configurable timeout and better error handling
     */
    private String callOpenAIWithTimeout(String prompt, int timeoutSeconds) throws Exception {
    try {
        // Automatically prepend PDF context to every prompt
        String pdfContext = getAutoPdfContext();
        String enhancedPrompt = pdfContext + prompt;
        
        System.out.println("Enhanced prompt length: " + enhancedPrompt.length() + " chars (includes PDF context)");
        
        // Prepare OpenAI API request
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", 
            "You are a federal HR specialist with access to official OPM standards. " +
            "Use the provided reference materials to ensure accurate classification."));
        messages.add(new Message("user", enhancedPrompt));

        OpenAIRequest openaiRequest = new OpenAIRequest("gpt-3.5-turbo", messages, false);
        openaiRequest.setMax_tokens(3000);
        openaiRequest.setTemperature(0.3);

        ObjectMapper objectMapper = new ObjectMapper();
        String requestBody = objectMapper.writeValueAsString(openaiRequest);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + pdService.getOpenaiApiKey())
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> openaiResponse = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (openaiResponse.statusCode() != 200) {
            throw new Exception("OpenAI API returned status: " + openaiResponse.statusCode() + " - " + openaiResponse.body());
        }

        JsonNode responseJson = objectMapper.readTree(openaiResponse.body());
        if (responseJson.has("choices") && responseJson.get("choices").size() > 0) {
            JsonNode choice = responseJson.get("choices").get(0);
            if (choice.has("message") && choice.get("message").has("content")) {
                String content = choice.get("message").get("content").asText().trim();
                if (content.isEmpty()) {
                    throw new Exception("Empty response from OpenAI API");
                }
                return content;
            }
        }

        throw new Exception("Invalid response format from OpenAI API");
    } catch (Exception e) {
        System.err.println("OpenAI API call failed: " + e.getMessage());
        throw new Exception("Failed to get AI analysis: " + e.getMessage());
    }
}

    /**
     * Async version using CompletableFuture for non-blocking execution
     */
    @PostMapping("/rewrite-duties-async")
    public CompletableFuture<Map<String, String>> rewriteDutiesAsync(@RequestBody Map<String, String> body) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return rewriteDutiesOptimized(body);
            } catch (Exception e) {
                return Map.of("rewritten", "Error: " + e.getMessage(), "error", "true");
            }
        });
    }

    /**
     * POST endpoint to recommend job series based on duties/responsibilities using OpenAI API.
     */
    @PostMapping("/recommend-series")
public Map<String, Object> recommendSeries(@RequestBody Map<String, String> body) throws Exception {
    String duties = body.getOrDefault("duties", "");
    String supervisoryLevel = body.getOrDefault("supervisoryLevel", "Non-Supervisory");
    
    if (duties.isBlank()) {
        return Map.of("recommendations", new ArrayList<>(), "error", "No duties provided");
    }

    try {
        // --- Use OpenAI classification for top 3 series ---
        List<Map<String, String>> classifications = classifySeries(body);

        List<Map<String, Object>> recommendations = new ArrayList<>();
        for (Map<String, String> classification : classifications) {
            String code = classification.getOrDefault("seriesCode", "");
            String title = classification.getOrDefault("seriesTitle", "");

            Map<String, Object> recommendation = new HashMap<>();
            recommendation.put("code", code);
            recommendation.put("title", title);
            recommendation.put("confidence", 0.99); // Direct from OpenAI
            recommendation.put("topPosition", title);

            recommendations.add(recommendation);
        }

        // Get GS grade relevancy from AI with supervisory level
        List<Map<String, Object>> gradeRelevancy = getAIGSGradeRelevancy(duties, supervisoryLevel);

        String gsGrade = gradeRelevancy != null && !gradeRelevancy.isEmpty()
            ? (String) gradeRelevancy.get(0).get("grade")
            : "GS-13";

        Map<String, Object> result = new HashMap<>();
        result.put("recommendations", recommendations);
        result.put("gsGrade", gsGrade);
        result.put("gradeRelevancy", gradeRelevancy);
        result.put("supervisoryLevel", supervisoryLevel); // Include for consistency

        return result;
    } catch (Exception e) {
        System.err.println("OpenAI classification failed: " + e.getMessage());
        return Map.of("recommendations", new ArrayList<>(), "error", "OpenAI classification failed");
    }
}

    /**
     * Escapes special JSON characters in a string.
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * GET endpoint to fetch fresh position data for a specific job series
     */
    @GetMapping("/job-series/{code}/positions")
public ResponseEntity<List<String>> getPositionsForSeries(@PathVariable String code) {
    try {
        Map<String, Map<String, Object>> allSeries = jobSeriesService.getJobSeriesData();
        if (allSeries.containsKey(code)) {
            Map<String, Object> seriesData = allSeries.get(code);
            @SuppressWarnings("unchecked")
            List<String> positions = (List<String>) seriesData.get("positions");
            if (positions != null && !positions.isEmpty()) {
                // Case-insensitive dedupe, keep first capitalization, normalize spaces
                Map<String, String> seen = new LinkedHashMap<>();
                for (String pos : positions) {
                    if (pos == null) continue;
                    String norm = pos.replaceAll("\\s+", " ").trim();
                    if (norm.isEmpty()) continue;
                    String key = norm.toLowerCase(Locale.ROOT);
                    seen.putIfAbsent(key, norm);
                }
                List<String> validPositions = new ArrayList<>(seen.values());
                return ResponseEntity.ok(validPositions);
            }
        }
        return ResponseEntity.ok(new ArrayList<>());
    } catch (Exception e) {
        System.err.println("Error fetching positions for series " + code + ": " + e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ArrayList<>());
    }
}

    /**
     * Alternative endpoint that gets fresh data directly from JobSeriesService
     */
    @GetMapping("/job-series/{code}/fresh-positions")
    public ResponseEntity<List<String>> getFreshPositionsForSeries(@PathVariable String code) {
        try {
            return getPositionsForSeries(code);
            
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(new ArrayList<>());
        }
    }

    /**
     * Add this helper method (calls OpenAI or your logic)
     */
    @SuppressWarnings("unused")
    private String getAIGSGrade(String duties) throws Exception {
        String prompt = String.format("""
            Use the following links as guidelines and references: https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/
            Use this as a reference and guide: https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/
            Use this as a reference and guide: https://www.opm.gov/policy-data-oversight/classification-qualifications/classifying-general-schedule-positions/factor-evaluation-system/
    
            Based on the following federal job duties, recommend the most appropriate GS grade level (e.g., GS-11, GS-12, GS-13, GS-14).
            Only return the grade code (e.g., GS-13) and nothing else.

            Duties:
            %s
            """, duties);

        String response = callOpenAIWithTimeout(prompt, 10);
        // Extract GS grade from response (e.g., "GS-13")
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("GS-\\d{2}").matcher(response);
        if (m.find()) return m.group();
        return response.trim();
    }

    private List<Map<String, Object>> getAIGSGradeRelevancy(String duties, String supervisoryLevel) throws Exception {
    String pdfContext = getAutoPdfContext();
    
    String prompt = pdfContext + String.format("""
        Duties may be listed with percentages. Duties with higher percentages must be given greater weight and considered more important in your analysis. Percentages if present depict the importance of that duty.
        Use the official OPM two-grade interval system and the reference materials provided above for accurate grade determination.

        IMPORTANT: Consider the supervisory level when determining grade levels:
        - Non-Supervisory: Focus on technical complexity and individual contribution
        - Team Leader: Add 1 grade level for informal leadership responsibilities
        - Supervisor: Add 1-2 grade levels for formal supervisory duties
        - Manager: Add 2-3 grade levels for managerial responsibilities

        Supervisory Level: %s

        Based on the following federal job duties, supervisory level, and OPM standards provided above, 
        list the top 5 most likely GS grade levels for this position.
        
        Only consider two-grade interval grades (GS-5, GS-7, GS-9, GS-11, GS-12, GS-13, GS-14, GS-15) 
        unless the duties clearly fit a one-grade interval series.
        
        For each grade, provide a percentage likelihood (total should sum to 100).
        
        Respond ONLY in this JSON format:
        [
        {"grade": "GS-13", "percentage": 40},
        {"grade": "GS-12", "percentage": 25},
        {"grade": "GS-11", "percentage": 20},
        {"grade": "GS-09", "percentage": 10},
        {"grade": "GS-07", "percentage": 5}
        ]

        Duties:
        %s
        """, supervisoryLevel != null ? supervisoryLevel : "Non-Supervisory", duties);

    String response = callOpenAIWithTimeout(prompt, 15);

    ObjectMapper mapper = new ObjectMapper();
    List<Map<String, Object>> grades;
    try {
        grades = mapper.readValue(response, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception e) {
        // Fallback: try to extract grades with regex if AI returns text
        grades = new ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("GS-\\d{2}");
        java.util.regex.Matcher m = p.matcher(response);
        while (m.find() && grades.size() < 5) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("grade", m.group());
            entry.put("percentage", 100 / (grades.size() + 1));
            grades.add(entry);
        }
    }

    // Sanitize and normalize percentages
    double total = 0;
    for (Map<String, Object> entry : grades) {
        Object pctObj = entry.get("percentage");
        double pct = 0;
        if (pctObj instanceof Number) {
            pct = ((Number) pctObj).doubleValue();
        } else if (pctObj instanceof String) {
            try {
                pct = Double.parseDouble((String) pctObj);
            } catch (Exception ignore) {}
        }
        // Clamp to [0, 100]
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        entry.put("percentage", pct);
        total += pct;
    }
    
    // Normalize so total is 100
    if (total > 0 && Math.abs(total - 100) > 0.01) {
        for (Map<String, Object> entry : grades) {
            double pct = ((Number) entry.get("percentage")).doubleValue();
            double norm = Math.round((pct / total) * 100);
            entry.put("percentage", norm);
        }
        // Fix rounding error on last item
        double newTotal = grades.stream().mapToDouble(e -> ((Number) e.get("percentage")).doubleValue()).sum();
        if (Math.abs(newTotal - 100) > 0.01 && !grades.isEmpty()) {
            double diff = 100 - newTotal;
            Map<String, Object> last = grades.get(grades.size() - 1);
            last.put("percentage", ((Number) last.get("percentage")).doubleValue() + diff);
        }
    }
    
    List<String> allowedGrades = List.of("GS-5", "GS-7", "GS-9", "GS-11", "GS-12", "GS-13", "GS-14", "GS-15");
    grades = grades.stream()
        .filter(entry -> allowedGrades.contains(String.valueOf(entry.get("grade"))))
        .collect(Collectors.toList());

    return grades;
}

private boolean isSupervisoryPosition(String supervisoryLevel) {
    if (supervisoryLevel == null) return false;
    String s = supervisoryLevel.trim().toLowerCase();

    // Explicit supervisory titles only (Team Lead treated as non‑supervisory)
    if (s.equals("supervisor") || s.equals("manager")) return true;

    // Also accept common variants that explicitly indicate supervisory authority
    if (s.contains("supervisor") || s.contains("manager")) return true;

    // Explicitly exclude common "lead" variants (team lead, technical lead) as non‑supervisory
    if (s.contains("team lead") || s.contains("technical lead") || s.equals("lead")) return false;

    return false;
}

// Modified generateEvaluationStatement that routes to correct guide
@PostMapping("/generate-evaluation-statement")
public void generateEvaluationStatement(@RequestBody Map<String, String> body, HttpServletResponse response) throws Exception {
    response.setContentType("text/event-stream");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-cache");
    response.setHeader("Connection", "keep-alive");
    response.setHeader("Access-Control-Allow-Origin", "*");

    PrintWriter writer = response.getWriter();

    try {
        String duties = body.getOrDefault("duties", "");
        String gsGrade = body.getOrDefault("gsGrade", "");
        String positionTitle = body.getOrDefault("positionTitle", "");
        String supervisoryLevel = body.getOrDefault("supervisoryLevel", "Non-Supervisory");

        if (isSupervisoryPosition(supervisoryLevel)) {
            // Route to supervisory evaluation using GSSG
            generateSupervisoryEvaluation(duties, gsGrade, positionTitle, supervisoryLevel, writer);
        } else {
            // Route to standard FES evaluation
            generateNonSupervisoryEvaluation(duties, gsGrade, positionTitle, supervisoryLevel, writer);
        }

    } catch (Exception e) {
        System.err.println("EXCEPTION in generateEvaluationStatement: " + e.getMessage());
        e.printStackTrace();
        writer.println("data: {\"error\":\"" + escapeJson(e.getMessage()) + "\"}\n");
    } finally {
        try {
            writer.flush();
            writer.close();
        } catch (Exception e) {
            System.err.println("Error closing writer: " + e.getMessage());
        }
    }
}

private void generateSupervisoryEvaluation(String duties, String gsGrade, String positionTitle,
                                           String supervisoryLevel, PrintWriter writer) throws Exception {

    System.out.println("Generating GSSG Supervisory Evaluation for: " + positionTitle);

    if (gsGrade == null || gsGrade.trim().isEmpty()) gsGrade = "GS-13";

    // supervisoryLevels now may include keys "4A" and "4B"
    Map<String, String> supervisoryLevels = new HashMap<>();
    Map<String, String> defaults = getDefaultGssgLevelsForGrade(gsGrade);
    for (int i = 1; i <= 6; i++) {
        if (i == 4) {
            // ensure both 4A and 4B present
            supervisoryLevels.put("4A", defaults.getOrDefault("4A", "4A-2"));
            supervisoryLevels.put("4B", defaults.getOrDefault("4B", "4B-2"));
        } else {
            supervisoryLevels.put(String.valueOf(i), defaults.getOrDefault(String.valueOf(i), "1-1"));
        }
    }

    // Intelligent starting levels (special-case factor 4 to set both 4A and 4B)
    for (int i = 1; i <= 6; i++) {
        if (i == 4) {
            String a = getIntelligentGssgLevel4A(duties, gsGrade);
            String b = getIntelligentGssgLevel4B(duties, gsGrade);
            if (a != null && !a.isBlank()) supervisoryLevels.put("4A", a);
            if (b != null && !b.isBlank()) supervisoryLevels.put("4B", b);
            continue;
        }
        String intelligent = getIntelligentGssgLevel(i, duties, gsGrade);
        if (intelligent != null && !intelligent.isBlank()) supervisoryLevels.put(String.valueOf(i), intelligent);
    }

    // avoid everything being identical
    ensureDiverseGssgLevels(supervisoryLevels, gsGrade);

    // Compute points using GSSG mappings (sum includes 4A + 4B)
    Map<String, Integer> pointsMap = new HashMap<>();
    int total = 0;
    for (int i = 1; i <= 6; i++) {
        if (i == 4) {
            String lvlA = normalizeGssgLevel("4A", supervisoryLevels.get("4A"));
            String lvlB = normalizeGssgLevel("4B", supervisoryLevels.get("4B"));
            supervisoryLevels.put("4A", lvlA);
            supervisoryLevels.put("4B", lvlB);
            int ptsA = getGssgPointsForLevel("4A", lvlA);
            int ptsB = getGssgPointsForLevel("4B", lvlB);
            pointsMap.put("4A", ptsA);
            pointsMap.put("4B", ptsB);
            total += ptsA + ptsB;
        } else {
            String f = String.valueOf(i);
            String lvl = normalizeGssgLevel(f, supervisoryLevels.get(f));
            supervisoryLevels.put(f, lvl);
            int pts = getGssgPointsForLevel(f, lvl);
            pointsMap.put(f, pts);
            total += pts;
        }
    }

    int minPoints = getMinPointsForGrade(gsGrade);
    int maxPoints = getMaxPointsForGrade(gsGrade);

    // Adjustment loop: treat 4A/4B as separate adjustable factors
    if (total < minPoints || total > maxPoints) {
        int targetMid = (minPoints + maxPoints) / 2;
        int diff = targetMid - total;
        String[] incOrder = {"1","5","3","2","4A","4B","6"};
        String[] decOrder = {"6","4B","4A","2","3","5","1"};
        boolean changed = true;
        int safety = 0;
        while (Math.abs(diff) > 25 && changed && safety++ < 100) {
            changed = false;
            String[] order = diff > 0 ? incOrder : decOrder;
            for (String fnum : order) {
                String cur = supervisoryLevels.get(fnum);
                if (cur == null) continue;
                String candidate = diff > 0 ? getNextHigherValidGssgLevel(fnum, cur) : getNextLowerValidGssgLevel(fnum, cur);
                if (candidate == null) continue;
                int oldPts = getGssgPointsForLevel(fnum, cur);
                int newPts = getGssgPointsForLevel(fnum, candidate);
                int change = newPts - oldPts;
                if (change == 0) continue;
                if ((diff > 0 && change > 0) || (diff < 0 && change < 0)) {
                    supervisoryLevels.put(fnum, preserveFactorLetter(cur, candidate));
                    pointsMap.put(fnum, newPts);
                    total += change;
                    diff = targetMid - total;
                    changed = true;
                    if (Math.abs(diff) <= 25) break;
                }
            }
        }
    }

    // Final sanity attempt: try to set Factor1 to get closer if still outside
    if (total < minPoints || total > maxPoints) {
        int mid = (minPoints + maxPoints) / 2;
        for (int lvl = getGssgMaxLevelForFactor(1); lvl >= 1; lvl--) {
            String cand = "1-" + lvl;
            int candPts = getGssgPointsForLevel("1", cand);
            int otherSum = total - pointsMap.get("1");
            int desired = mid - otherSum;
            if (Math.abs(candPts - desired) <= 200) {
                supervisoryLevels.put("1", cand);
                pointsMap.put("1", candPts);
                total = otherSum + candPts;
                break;
            }
        }
    }

    // --- TRY AI FIRST (use PDFs context + prompt). FALLBACK to deterministic output if AI fails or seems incomplete ---
    String aiPrompt = getAutoPdfContext() + String.format("""
        You are an expert federal HR specialist. Using the GSSG (6-factor) produce a supervisory
        factor analysis for the position below. Put factor titles, level and exact points in the
        factor header lines (e.g., "FACTOR 1 - PROGRAM SCOPE AND EFFECT Level 1-3, 550 Points").
        Provide a substantive 3-5 sentence rationale for each factor that:
         - references specific duties,
         - explains why the assigned level exceeds the previous level, and
         - explains why it does not meet the next higher level.
        Use the exact factor titles and include separate 4A (Nature) and 4B (Purpose) entries for Factor 4.
        Target Grade: %s
        Position Title: %s

        Position Duties:
        %s
        """, gsGrade, positionTitle, duties == null ? "" : duties);

    String aiResponse = null;
    try {
        aiResponse = callOpenAIWithTimeout(aiPrompt, 120);
    } catch (Exception e) {
        System.err.println("Supervisory AI call failed: " + e.getMessage());
    }

    String finalEval = "";
    boolean aiAccepted = false;
    if (aiResponse != null && !aiResponse.isBlank()) {
        String sanitized = fixPDFormatting(sanitizeAiNotes(aiResponse));
        // remove leading "Rationale:" labels from AI so we can enforce our full sentences
        sanitized = sanitized.replaceAll("(?m)^\\s*Rationale:\\s*", "");
        // basic heuristics to ensure AI returned factors with levels/points in headers
        String upper = sanitized.toUpperCase();
        if (upper.contains("FACTOR 1") || upper.contains("FACTOR 4A") || upper.contains("FACTOR 4B") ||
            Pattern.compile("(?s)FACTOR\\s+1\\b.*LEVEL\\s+1-\\d+.*POINTS", Pattern.CASE_INSENSITIVE).matcher(sanitized).find()) {
            // Accept AI output but rebuild headers/rationales to guarantee correct names, normalized levels and substantive rationales
            // We'll extract any AI-provided nuance but enforce our header/points/rationale format.
            aiAccepted = true;

            // Build authoritative evaluation using either AI levels if valid or our computed supervisoryLevels
            StringBuilder sb = new StringBuilder();
            sb.append("SUPERVISORY FACTOR ANALYSIS (GSSG - 6 factors)\n\n");

            for (int i = 1; i <= 6; i++) {
                if (i == 4) {
                    // 4A
                    String lvlA = supervisoryLevels.getOrDefault("4A", extractLevelFromAi(sanitized, "4A", "4A"));
                    lvlA = normalizeGssgLevel("4A", lvlA);
                    int ptsA = getGssgPointsForLevel("4A", lvlA);
                    sb.append("FACTOR 4A - PERSONAL CONTACTS (NATURE OF CONTACTS) Level ").append(lvlA).append(", ").append(ptsA).append(" Points\n\n");
                    sb.append(buildGssgRationale(4, lvlA, duties, "4A")).append("\n\n");

                    // 4B
                    String lvlB = supervisoryLevels.getOrDefault("4B", extractLevelFromAi(sanitized, "4B", "4B"));
                    lvlB = normalizeGssgLevel("4B", lvlB);
                    int ptsB = getGssgPointsForLevel("4B", lvlB);
                    sb.append("FACTOR 4B - PERSONAL CONTACTS (PURPOSE OF CONTACTS) Level ").append(lvlB).append(", ").append(ptsB).append(" Points\n\n");
                    sb.append(buildGssgRationale(4, lvlB, duties, "4B")).append("\n\n");
                    continue;
                }

                String f = String.valueOf(i);
                String aiLevel = extractLevelFromAi(sanitized, "Factor " + i, null);
                String lvl = supervisoryLevels.getOrDefault(f, aiLevel);
                lvl = normalizeGssgLevel(f, lvl);
                int pts = getGssgPointsForLevel(f, lvl);
                sb.append("FACTOR ").append(i).append(" - ").append(getSupervisoryFactorName(i))
                  .append(" Level ").append(lvl).append(", ").append(pts).append(" Points\n\n");
                sb.append(buildGssgRationale(i, lvl, duties, null)).append("\n\n");
            }

            sb.append(String.format("**Total Points: %d**\n", total));
            sb.append(String.format("**Final Grade: %s**\n", gsGrade));
            sb.append(String.format("**Grade Range: %s**\n", getPointRangeForGrade(gsGrade)));

            finalEval = fixPDFormatting(sb.toString());
        } else {
            System.out.println("AI response did not include expected factor headers/levels; using deterministic fallback.");
        }
    }

    if (!aiAccepted) {
        // deterministic fallback builder (enforced titles, levels, substantive rationales)
        StringBuilder sb = new StringBuilder();
        sb.append("SUPERVISORY FACTOR ANALYSIS (GSSG - 6 factors)\n\n");
        for (int i = 1; i <= 6; i++) {
            if (i == 4) {
                String lvlA = supervisoryLevels.get("4A");
                int ptsA = pointsMap.getOrDefault("4A", getGssgPointsForLevel("4A", lvlA));
                sb.append("FACTOR 4A - PERSONAL CONTACTS (NATURE OF CONTACTS) Level ").append(lvlA).append(", ").append(ptsA).append(" Points\n\n");
                sb.append(buildGssgRationale(4, lvlA, duties, "4A")).append("\n\n");

                String lvlB = supervisoryLevels.get("4B");
                int ptsB = pointsMap.getOrDefault("4B", getGssgPointsForLevel("4B", lvlB));
                sb.append("FACTOR 4B - PERSONAL CONTACTS (PURPOSE OF CONTACTS) Level ").append(lvlB).append(", ").append(ptsB).append(" Points\n\n");
                sb.append(buildGssgRationale(4, lvlB, duties, "4B")).append("\n\n");
                continue;
            }

            String f = String.valueOf(i);
            String lvl = supervisoryLevels.get(f);
            int pts = pointsMap.getOrDefault(f, getGssgPointsForLevel(f, lvl));
            sb.append("FACTOR ").append(i).append(" - ").append(getSupervisoryFactorName(i))
            .append(" Level ").append(lvl).append(", ").append(pts).append(" Points\n\n");
            sb.append(buildGssgRationale(i, lvl, duties, null)).append("\n\n");
        }

        sb.append(String.format("**Total Points: %d**\n", total));
        sb.append(String.format("**Final Grade: %s**\n", gsGrade));
        sb.append(String.format("**Grade Range: %s**\n", getPointRangeForGrade(gsGrade)));

        finalEval = fixPDFormatting(sb.toString());
    }

    // final sanitize: remove any leading "Rationale:" tokens left over
    finalEval = finalEval.replaceAll("(?m)^\\s*Rationale:\\s*", "");

    writer.println("data: {\"evaluationStatement\":\"" + escapeJson(finalEval) + "\"}\n");
    writer.flush();
    return;
}

/** Normalize level strings so headers always use "X-Y" or include subfactor letter (e.g., "4A-2") */
private String normalizeGssgLevel(String factorId, String level) {
    if (level == null) return factorId.replaceAll("[^0-9A-Z-]", "") + "-1";
    level = level.trim();
    // If already in form "X-Y" or "4A-2", return as-is
    if (level.matches("\\d+-\\d+") || level.matches("\\d+-[A-Z]") || level.matches("\\d+[A-Z]?[-]\\d+")) {
        return level;
    }
    // If level is a single digit like "3" -> prefix with factor number when possible
    if (level.matches("\\d+")) {
        // determine factor number portion
        String prefix = factorId;
        // if factorId is "4A" or "4B", use that prefix
        if (!factorId.matches("\\d+")) {
            return factorId + "-" + level;
        }
        return factorId + "-" + level;
    }
    // If level looks like "A" or "B" -> map letter to numeric
    if (level.matches("[A-Z]")) {
        int num = (level.charAt(0) - 'A') + 1;
        return factorId + "-" + num;
    }
    // Fallback: return unchanged
    return level;
}

/** Extract a factor level from AI text if present; returns null if not found. */
private String extractLevelFromAi(String aiText, String factorSearch, String preferId) {
    if (aiText == null) return null;
    // Try patterns like "FACTOR 1 - NAME Level 1-3, 550 Points" or "Factor 4A - ... Level 4-2, 50 Points"
    Pattern p = Pattern.compile("(?i)(" + Pattern.quote(factorSearch) + "|FACTOR\\s+" + Pattern.quote(factorSearch.replaceAll("[^0-9]", "")) + "[A-Z]?)\\b[^\\n]{0,120}?Level\\s*(\\d+-[A-Za-z0-9]+)\\s*,\\s*(\\d+)\\s*Points");
    Matcher m = p.matcher(aiText);
    if (m.find()) {
        return m.group(2);
    }
    // try looser match: "Level 1-3" near factor header
    p = Pattern.compile("(?i)" + Pattern.quote(factorSearch) + "[\\s\\S]{0,120}?Level\\s*(\\d+-\\d+)");
    m = p.matcher(aiText);
    if (m.find()) return m.group(1);
    // fallback: try any Level X-Y nearest the factor name
    p = Pattern.compile("(?i)Level\\s*(\\d+-[A-Za-z0-9]+)");
    m = p.matcher(aiText);
    if (m.find()) return m.group(1);
    return null;
}

private String buildGssgRationale(int factorNum, String level, String duties, String subFactor) {
    // CRITICAL: Add null safety checks at the very beginning
    if (level == null) {
        System.out.println("WARNING: buildGssgRationale called with null level for factor " + factorNum);
        level = factorNum + "-1";
    }
    if (duties == null) {
        System.out.println("WARNING: buildGssgRationale called with null duties for factor " + factorNum);
        duties = "";
    }
    
    String factorName = (factorNum >= 1 && factorNum <= 6) ? getSupervisoryFactorName(factorNum) : "Factor " + factorNum;
    String dutiesSnippet = shortDutySnippet(duties);
    String assigned = level;
    int pts = getGssgPointsForLevel(subFactor == null ? String.valueOf(factorNum) : subFactor, level);

    String prev = (subFactor == null) ? getNextLowerValidGssgLevel(String.valueOf(factorNum), level)
                                      : getNextLowerValidGssgLevel(subFactor, level);
    String next = (subFactor == null) ? getNextHigherValidGssgLevel(String.valueOf(factorNum), level)
                                      : getNextHigherValidGssgLevel(subFactor, level);

    StringBuilder sb = new StringBuilder();

    // Sentence 1: What the assigned level is and why (tie to duties)
    sb.append(String.format("%s assigned at Level %s (%d points) is appropriate because %s. ",
            factorName, assigned, pts,
            inferLevelJustification(factorNum, level, dutiesSnippet)));

    // Sentence 2: Why it exceeds the previous (explicit comparison)
    if (prev != null) {
        sb.append(String.format("It exceeds %s because %s, which demonstrates greater %s than that lower level requires. ",
                prev,
                inferSpecificExamplesForExceeding(factorNum, dutiesSnippet, prev),
                inferContrastPhrase(factorNum, level, prev)
        ));
    } else {
        sb.append("There is no lower level to compare for this factor. ");
    }

    // Sentence 3: Why it does not meet the next higher level (explicit missing elements)
    if (next != null) {
        sb.append(String.format("It does not meet %s because that level requires %s, which are not evident in the duties (for example %s).",
                next,
                inferNextLevelRequirement(factorNum, next),
                dutiesSnippet));
    } else {
        sb.append("There is no higher level beyond this assignment.");
    }

    return sb.toString().trim();
}

/** Helper used by buildGssgRationale to craft the opening justification sentence */
private String inferLevelJustification(int factorNum, String level, String dutiesSnippet) {
    // Short factor-specific justification phrased to reference duties snippet
    switch (factorNum) {
        case 1:
            return "the duties (" + dutiesSnippet + ") require the indicated breadth and impact of program oversight, demonstrable technical/managerial responsibility, and independent application of specialized knowledge";
        case 2:
            return "the organizational context and reporting relationships described (e.g., reporting to senior program managers or delegating authority) match the independence and decision-making shown at this level";
        case 3:
            return "the incumbent must interpret policy, resolve ambiguous guidance, and adapt procedures to novel situations consistent with this level of delegated authority";
        case 4:
            return "contacts are with the types of internal and external stakeholders that require non-routine preparation, high-level coordination, and persuasive communication";
        case 5:
            return "the work directed has measurable programmatic effect beyond unit boundaries and affects significant organizational or stakeholder outcomes";
        case 6:
            return "the conditions under which supervision is exercised (e.g., dispersed teams, contractors, fluctuating workforce) increase the complexity and demand this level of oversight";
        default:
            return "the duties justify this level based on scope, responsibility, and impact";
    }
}

/** Helper used by buildGssgRationale to craft an example that shows why it exceeds previous level */
private String inferSpecificExamplesForExceeding(int factorNum, String dutiesSnippet, String prevLevel) {
    // Use duties snippet and factor cues to produce a concrete explanatory phrase
    switch (factorNum) {
        case 1:
            return "the position directs multiple program segments or a program with multi-jurisdictional impact rather than single-site or routine tasks";
        case 2:
            return "the incumbent sets priorities, approves methods, or functions with broad administrative discretion rather than following tightly specified procedures";
        case 3:
            return "the incumbent must develop or adapt guidance and exercise judgment beyond routine procedural interpretation";
        case 4:
            return "contacts include senior managers or external officials requiring tailored briefings and negotiation, not just routine information exchange";
        case 5:
            return "decisions influence resources, continuity of services, or program direction across organizations rather than only within a single unit";
        case 6:
            return "regular management of variable or technically complex work (e.g., contractors or multi-site operations) increases oversight requirements above the lower level";
        default:
            return "the duties demonstrate greater complexity and wider impact than the lower level";
    }
}

private String getIntelligentGssgLevel(int factorNum, String duties, String gsGrade) {
    // REPLACED: richer, factor-specific heuristics to avoid defaulting everything to 1-3
    String lower = duties == null ? "" : duties.toLowerCase();

    int gradeBias = 0;
    if (gsGrade != null) {
        try {
            gradeBias = Integer.parseInt(gsGrade.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {}
    }

    boolean national = containsAny(lower, new String[]{"national", "government-wide", "agencywide", "preeminent", "national impact"});
    boolean policy = containsAny(lower, new String[]{"policy", "regulation", "legislation", "guidance", "precedent"});
    boolean supervises = containsAny(lower, new String[]{"supervises", "manages", "leads", "directs", "supervisor", "manager"});
    boolean technical = containsAny(lower, new String[]{"technical", "subject matter", "sme", "expert", "analysis", "research", "design"});
    boolean routine = containsAny(lower, new String[]{"clerical", "routine", "assist", "support", "follows procedures", "entry level"});
    boolean external = containsAny(lower, new String[]{"congress", "stakeholder", "contractor", "industry", "public", "external"});

    switch (factorNum) {
        case 1: // PROGRAM SCOPE AND EFFECT (1-1 .. 1-5)
            if (national || policy || (technical && gradeBias >= 14)) return "1-5";
            if (technical || supervises || external) return "1-4";
            if (containsAny(lower, new String[]{"major program", "multi-state", "regional", "large installation"})) return "1-4";
            if (routine) return "1-2";
            return "1-3";
        case 2: // ORGANIZATIONAL SETTING (2-1..2-3)
            if (national || containsAny(lower, new String[]{"ses", "executive", "flag", "general officer"})) return "2-3";
            if (supervises || containsAny(lower, new String[]{"deputy", "assistant chief", "reports to chief"})) return "2-2";
            return "2-1";
        case 3: // SUPERVISORY & MANAGERIAL AUTHORITY EXERCISED
            if (supervises && containsAny(lower, new String[]{"final authority", "personnel actions", "budget authority"})) return "3-4";
            if (supervises) return "3-3";
            if (technical && containsAny(lower, new String[]{"contract oversight", "technical oversight"})) return "3-3";
            return "3-2";
        case 4: // PERSONAL CONTACTS (Nature) - numeric 4-1..4-4 used as shorthand (4A/4B handled separately elsewhere)
            if (external && containsAny(lower, new String[]{"senior", "executive", "congress", "media"})) return "4-4";
            if (external || containsAny(lower, new String[]{"contracting officials", "regional officials"})) return "4-3";
            if (containsAny(lower, new String[]{"peers", "staff", "local"})) return "4-2";
            return "4-1";
        case 5: // DIFFICULTY OF TYPICAL WORK DIRECTED
            if (containsAny(lower, new String[]{"gs-13", "gs-14", "specialist", "highly technical", "complex program"})) return "5-6";
            if (technical || supervises) return "5-5";
            if (containsAny(lower, new String[]{"moderate", "field office", "section level"})) return "5-4";
            return "5-3";
        case 6: // OTHER CONDITIONS
            if (containsAny(lower, new String[]{"hazard", "emergency", "high risk", "special staffing", "physically dispersed"})) return "6-4";
            if (containsAny(lower, new String[]{"coordination", "integration", "complex oversight"})) return "6-3";
            return "6-2";
        default:
            return factorNum + "-1";
    }
}

/** Ensure supervisoryLevels are not all identical (simple deterministic diversification) */
private void ensureDiverseGssgLevels(Map<String, String> supervisoryLevels, String gsGrade) {
    if (supervisoryLevels == null || supervisoryLevels.isEmpty()) return;
    // If every value identical (e.g., all "1-3"), bump critical factors toward higher valid levels
    java.util.Set<String> unique = new java.util.HashSet<>(supervisoryLevels.values());
    if (unique.size() == 1) {
        // Prefer to bump Factor 1 and Factor 5 by one or two steps depending on target grade
        String f1 = supervisoryLevels.getOrDefault("1", "1-3");
        String f5 = supervisoryLevels.getOrDefault("5", "5-3");

        int gs = 13;
        try { gs = Integer.parseInt(gsGrade.replaceAll("[^0-9]", "")); } catch (Exception ignored) {}

        int bump = gs >= 14 ? 2 : 1;

        String newF1 = f1;
        for (int i = 0; i < bump; i++) {
            String cand = getNextHigherValidGssgLevel("1", newF1);
            if (cand == null) break;
            newF1 = cand;
        }
        supervisoryLevels.put("1", newF1);

        String newF5 = f5;
        for (int i = 0; i < bump; i++) {
            String cand = getNextHigherValidGssgLevel("5", newF5);
            if (cand == null) break;
            newF5 = cand;
        }
        supervisoryLevels.put("5", newF5);

        // Also nudge Factor 3 down or up to create variety
        String f3 = supervisoryLevels.getOrDefault("3", "3-3");
        String cand3 = getNextLowerValidGssgLevel("3", f3);
        if (cand3 != null) supervisoryLevels.put("3", cand3);
    }
}

/** GSSG-specific point mapping for Factors 1..6 with separate handling for 4A and 4B */
private int getGssgPointsForLevel(String factorNum, String level) {
    if (factorNum == null || level == null) return 0;
    
    try {
        // Handle 4A subfactor
        if (factorNum.equalsIgnoreCase("4A")) {
            switch (level) {
                case "4A-1": case "4-1": return 25;
                case "4A-2": case "4-2": return 50;
                case "4A-3": case "4-3": return 75;
                case "4A-4": case "4-4": return 100;
                default:
                    // Parse numeric level if pattern doesn't match
                    if (level.contains("-")) {
                        String[] parts = level.split("-");
                        int lv = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
                        switch (lv) {
                            case 1: return 25;
                            case 2: return 50;
                            case 3: return 75;
                            case 4: return 100;
                        }
                    }
                    return 0;
            }
        }
        
        // Handle 4B subfactor
        if (factorNum.equalsIgnoreCase("4B")) {
            switch (level) {
                case "4B-1": case "4-1": return 30;
                case "4B-2": case "4-2": return 75;
                case "4B-3": case "4-3": return 100;
                case "4B-4": case "4-4": return 125;
                default:
                    // Parse numeric level if pattern doesn't match
                    if (level.contains("-")) {
                        String[] parts = level.split("-");
                        int lv = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
                        switch (lv) {
                            case 1: return 30;
                            case 2: return 75;
                            case 3: return 100;
                            case 4: return 125;
                        }
                    }
                    return 0;
            }
        }

        // Existing mappings for other factors (1-6)
        switch (factorNum) {
            case "1":
                switch (level) {
                    case "1-1": return 175;
                    case "1-2": return 350;
                    case "1-3": return 550;
                    case "1-4": return 775;
                    case "1-5": return 900;
                }
                break;
            case "2":
                switch (level) {
                    case "2-1": return 100;
                    case "2-2": return 250;
                    case "2-3": return 350;
                }
                break;
            case "3":
                switch (level) {
                    case "3-1": return 250;
                    case "3-2": return 450;
                    case "3-3": return 775;
                    case "3-4": return 900;
                }
                break;
            case "5":
                switch (level) {
                    case "5-1": return 75;
                    case "5-2": return 205;
                    case "5-3": return 340;
                    case "5-4": return 505;
                    case "5-5": return 650;
                    case "5-6": return 800;
                    case "5-7": return 930;
                    case "5-8": return 1030;
                }
                break;
            case "6":
                switch (level) {
                    case "6-1": return 310;
                    case "6-2": return 575;
                    case "6-3": return 975;
                    case "6-4": return 1120;
                    case "6-5": return 1225;
                    case "6-6": return 1325;
                }
                break;
            default:
                // FIX: Don't recursively call getPointsForLevel with GSSG data
                // Instead, return 0 for unknown factor/level combinations
                System.err.println("Unknown GSSG factor/level: " + factorNum + " / " + level);
                return 0;
        }

        // FIX: Remove the problematic recursive fallback
        // If we got here, the level wasn't found in the explicit mappings
        System.err.println("GSSG level not found: Factor " + factorNum + " Level " + level);
        return 0;
        
    } catch (Exception e) {
        System.err.println("Error in getGssgPointsForLevel: " + e.getMessage());
        return 0;
    }
}

/** Overload: accept string factorId (handles 4A/4B) to get max levels */
private int getGssgMaxLevelForFactor(String factorId) {
    if (factorId == null) return 1;
    if (factorId.equalsIgnoreCase("4A") || factorId.equalsIgnoreCase("4B")) return 4;
    try {
        int f = Integer.parseInt(factorId.replaceAll("[^0-9]", ""));
        return getGssgMaxLevelForFactor(f);
    } catch (Exception e) {
        return 1;
    }
}

private String getSupervisoryFactorName(int i) {
    switch (i) {
        case 1: return "PROGRAM SCOPE AND EFFECT";
        case 2: return "ORGANIZATIONAL SETTING";
        case 3: return "SUPERVISORY & MANAGERIAL AUTHORITY EXERCISED";
        case 4: return "PERSONAL CONTACTS (NATURE & PURPOSE)";
        case 5: return "DIFFICULTY OF TYPICAL WORK DIRECTED";
        case 6: return "OTHER CONDITIONS";
        default: return "Factor " + i;
    }
}

/** Original numeric version retained */
private int getGssgMaxLevelForFactor(int factorNum) {
    switch (factorNum) {
        case 1: return 5;
        case 2: return 3;
        case 3: return 4;
        case 4: return 4;
        case 5: return 8;
        case 6: return 6;
        default: return getMaxLevelForFactor(String.valueOf(factorNum));
    }
}

/** Support next/previous level for factor ids that may be "4A"/"4B" or numeric strings */
private String getNextHigherValidGssgLevel(String factorId, String currentLevel) {
    if (factorId == null || currentLevel == null) return null;
    try {
        // currentLevel like "4A-2" or "4-2" or "4B-3"
        String[] parts = currentLevel.split("-");
        if (parts.length != 2) return null;
        int cur = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
        int max = getGssgMaxLevelForFactor(factorId);
        if (cur < max) {
            String prefix = factorId;
            // normalize prefix for output (use "4A" or "4B" when factorId contains letter)
            if (!prefix.matches(".*[A-Z]$") && parts[0].matches("\\d+")) {
                // keep format consistent: return same prefix as current level if it contains letter, else use factorId
                prefix = parts[0].replaceAll("[^0-9]", "");
            }
            return prefix + "-" + (cur + 1);
        }
    } catch (Exception e) { }
    return null;
}

private String getNextLowerValidGssgLevel(String factorId, String currentLevel) {
    if (factorId == null || currentLevel == null) return null;
    try {
        String[] parts = currentLevel.split("-");
        if (parts.length != 2) return null;
        int cur = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
        if (cur > 1) {
            String prefix = factorId;
            if (!prefix.matches(".*[A-Z]$") && parts[0].matches("\\d+")) {
                prefix = parts[0].replaceAll("[^0-9]", "");
            }
            return prefix + "-" + (cur - 1);
        }
    } catch (Exception e) { }
    return null;
}

/** Intelligent suggestions for subfactor 4A (Nature) */
private String getIntelligentGssgLevel4A(String duties, String gsGrade) {
    String lower = duties == null ? "" : duties.toLowerCase();
    boolean high = containsAny(lower, new String[]{"influential", "congress", "executive", "national", "senior officials"});
    boolean mid = containsAny(lower, new String[]{"management", "leaders", "external stakeholders", "contracting officials"});
    boolean low = containsAny(lower, new String[]{"subordinates", "peers", "local", "routine contacts"});

    if (high) return "4A-4";
    if (mid) return "4A-3";
    if (low) return "4A-2";
    return "4A-1";
}

/** Intelligent suggestions for subfactor 4B (Purpose) */
private String getIntelligentGssgLevel4B(String duties, String gsGrade) {
    String lower = duties == null ? "" : duties.toLowerCase();
    boolean high = containsAny(lower, new String[]{"influence", "negotiate", "commit resources", "congress", "major policy", "represent"});
    boolean mid = containsAny(lower, new String[]{"coordinate", "resolve", "justify", "defend", "negotiate moderately"});
    boolean low = containsAny(lower, new String[]{"exchange information", "train", "advise", "guide"});

    if (high) return "4B-4";
    if (mid) return "4B-3";
    if (low) return "4B-2";
    return "4B-1";
}

/** Default GSSG levels per target grade used as starting/fallback values (include 4A/4B) */
private Map<String, String> getDefaultGssgLevelsForGrade(String grade) {
    Map<String, String> m = new HashMap<>();
    switch ((grade == null ? "GS-12" : grade).toUpperCase()) {
        case "GS-15":
            m.put("1","1-5"); m.put("2","2-3"); m.put("3","3-4"); m.put("4A","4A-4"); m.put("4B","4B-4"); m.put("5","5-8"); m.put("6","6-6");
            break;
        case "GS-14":
            m.put("1","1-5"); m.put("2","2-3"); m.put("3","3-4"); m.put("4A","4A-3"); m.put("4B","4B-3"); m.put("5","5-6"); m.put("6","6-5");
            break;
        case "GS-13":
            m.put("1","1-4"); m.put("2","2-2"); m.put("3","3-3"); m.put("4A","4A-3"); m.put("4B","4B-3"); m.put("5","5-5"); m.put("6","6-4");
            break;
        case "GS-12":
            m.put("1","1-3"); m.put("2","2-2"); m.put("3","3-3"); m.put("4A","4A-2"); m.put("4B","4B-2"); m.put("5","5-4"); m.put("6","6-3");
            break;
        case "GS-11":
            m.put("1","1-3"); m.put("2","2-2"); m.put("3","3-2"); m.put("4A","4A-2"); m.put("4B","4B-2"); m.put("5","5-3"); m.put("6","6-2");
            break;
        case "GS-9":
            m.put("1","1-2"); m.put("2","2-1"); m.put("3","3-2"); m.put("4A","4A-1"); m.put("4B","4B-1"); m.put("5","5-2"); m.put("6","6-1");
            break;
        default: // GS-13 fallback
            m.put("1","1-4"); m.put("2","2-2"); m.put("3","3-3"); m.put("4A","4A-3"); m.put("4B","4B-3"); m.put("5","5-5"); m.put("6","6-3");
            break;
    }
    return m;
}

// Original method renamed for non-supervisory positions
private String sanitizeAiNotes(String text) {
    if (text == null) return "";
    String cleaned = text;

    // Remove obvious single-line disclaimers
    cleaned = cleaned.replaceAll("(?im)^\\s*As an AI(?: model)?[,\\s\\S]*?(?:\\.|\\n)", "");
    cleaned = cleaned.replaceAll("(?im)^\\s*I(?:'m| am) unable to provide[\\s\\S]*?(?:\\.|\\n)", "");
    cleaned = cleaned.replaceAll("(?im)^\\s*I cannot provide[\\s\\S]*?(?:\\.|\\n)", "");
    cleaned = cleaned.replaceAll("(?im)^\\s*Please note[\\s\\S]*?(?:\\.|\\n)", "");
    cleaned = cleaned.replaceAll("(?im)^\\s*This is a hypothetical[\\s\\S]*?(?:\\.|\\n)", "");
    cleaned = cleaned.replaceAll("(?im)^\\s*However, I can provide[\\s\\S]*?(?:\\.|\\n)", "");

    // Remove any long "example" blocks that begin with common markers (e.g., "**GSSG SUPERVISORY EVALUATION**" or "EXAMPLE")
    cleaned = cleaned.replaceAll("(?is)\\*\\*GSSG SUPERVISORY EVALUATION\\*\\*[\\s\\S]*?(?:\\*\\*Grade Range:|\\z)", "");
    cleaned = cleaned.replaceAll("(?is)\\bEXAMPLE[:\\s][\\s\\S]*?(?:\\n\\n|\\z)", "");
    cleaned = cleaned.replaceAll("(?is)\\bExample[:\\s][\\s\\S]*?(?:\\n\\n|\\z)", "");

    // Remove any leading/trailing AI assistant chatter lines
    cleaned = cleaned.replaceAll("(?im)^\\s*(Assistant:|AI:).*$", "");

    // Strip out repeated "Please note" trailing boilerplate
    cleaned = cleaned.replaceAll("(?is)Please note[\\s\\S]*?$", "");

    // Trim excessive whitespace and ensure we return substantive text
    cleaned = cleaned.trim();

    // If after cleaning result is empty, fall back to original text trimmed
    if (cleaned.isEmpty()) return text == null ? "" : text.trim();

    return cleaned;
}

// Original method renamed for non-supervisory positions
private void generateNonSupervisoryEvaluation(String duties, String gsGrade, String positionTitle, 
                                              String supervisoryLevel, PrintWriter writer) throws Exception {
    
    System.out.println("Generating FES Non-Supervisory Evaluation for: " + positionTitle);
    
    Map<String, String> gradeMinimums = getMinimumFactorLevelsForGrade(gsGrade);
    int targetMinPoints = getMinPointsForGrade(gsGrade);
    int targetMaxPoints = getMaxPointsForGrade(gsGrade);

    String pdfContext = getAutoPdfContext();

    String prompt = String.format("""
            %s

            Duties may be listed with percentages. Duties with higher percentages must be given greater weight and considered more important in your analysis. Percentages if present depict the importance of that duty.

            You are creating an OPM factor evaluation for %s targeting %d-%d points for %s.

            CRITICAL REQUIREMENTS:
            - ALL 9 factors must be analyzed with specific levels and rationales.
            - Factor 1 minimum: %s, Factor 2 minimum: %s, Factor 5 minimum: %s.
            - For each factor, especially Factor 1, explain in 2-3 sentences WHY the selected level fits the work described, and WHY it does NOT fit the next lower or higher level (e.g., for 1-8, explain why not 1-7 or 1-9).
            - DO NOT mention GS grades, grade ranges, or point values in the rationale text.
            - DO NOT reference "Factor Level X-X" or point values in the explanation.
            - Focus on the WORK CHARACTERISTICS and specific duties that justify the selected level and distinguish it from adjacent levels.

            GRADE DIFFERENTIATION GUIDANCE:
            For %s positions, you must explain:
            - Why the work is MORE complex/responsible than the next lower level (what additional elements elevate it)
            - Why the work is LESS complex/responsible than the next higher level (what elements are not yet present)
            - Specific duty examples that demonstrate the appropriate level

            MANDATORY RESPONSE FORMAT (complete ALL 9 factors):

            **Factor 1 – Knowledge Required by the Position Level 1-X, XXX Points**
            [Explain the depth and breadth of knowledge required. Describe why this level of knowledge is appropriate for the work, and why it is not the next lower or higher level. Reference specific duties that demonstrate this knowledge level.]

            ... (repeat for other factors)

            **Total Points: [EXACT sum]**
            **Final Grade: %s**
            **Grade Range: %s**
            """,
                pdfContext,
                positionTitle,
                targetMinPoints, targetMaxPoints,
                gsGrade,
                gradeMinimums.get("f1"),
                gradeMinimums.get("f2"),
                gradeMinimums.get("f5"),
                gsGrade,
                gsGrade,
                getPointRangeForGrade(gsGrade)
            );

    // Request AI synchronously so we can enforce alignment before returning
    String aiResponse = null;
    try {
        aiResponse = callOpenAIWithTimeout(prompt, 120);
    } catch (Exception e) {
        System.err.println("Non-supervisory evaluation AI call failed: " + e.getMessage());
        writer.println("data: {\"error\":\"AI generation failed for non-supervisory evaluation.\"}\n");
        writer.flush();
        return;
    }

    if (aiResponse == null || aiResponse.trim().isEmpty()) {
        writer.println("data: {\"error\":\"No content generated by AI for non-supervisory evaluation.\"}\n");
        writer.flush();
        return;
    }

    // Remove AI notes/disclaimers aggressively
    String cleaned = sanitizeAiNotes(aiResponse);

    // First pass: enforce alignment using existing helper
    String aligned = enforceExactGradeAlignment(cleaned, gsGrade, targetMinPoints, targetMaxPoints);

    // Format
    String formattedEval = fixPDFormatting(aligned);

    // Re-extract factor points and levels to confirm totals
    Map<String, Integer> factorPoints = extractFactorPoints(formattedEval);
    Map<String, String> factorLevels = extractFactorLevels(formattedEval);

    // If extraction failed or totals are out of bounds, attempt deterministic adjustments
    boolean needsAdjustment = false;
    int totalPoints = 0;
    if (factorPoints == null || factorPoints.isEmpty()) {
        needsAdjustment = true;
    } else {
        totalPoints = factorPoints.values().stream().mapToInt(Integer::intValue).sum();
        if (totalPoints < targetMinPoints || totalPoints > targetMaxPoints) needsAdjustment = true;
    }

    if (needsAdjustment) {
        // Build lock maps from either extracted data or defaults
        if (factorLevels == null || factorLevels.size() != 9) factorLevels = getDefaultFactorLevels(gsGrade);
        if (factorPoints == null || factorPoints.size() != 9) factorPoints = getDefaultFactorPoints(gsGrade);

        // Try to move total towards the grade midpoint using valid level steps
        int targetMid = (targetMinPoints + targetMaxPoints) / 2;
        totalPoints = factorPoints.values().stream().mapToInt(Integer::intValue).sum();
        int diff = targetMid - totalPoints;

        // Prioritize factors for adjustment: increase priority => 1,5,2,4,3,6,7,8,9 ; decrease priority reversed
        String[] incOrder = {"1","5","2","4","3","6","7","8","9"};
        String[] decOrder = {"9","8","7","6","3","4","2","5","1"};

        // Repeat adjustments until close to midpoint or no changes possible
        boolean changed = true;
        int safety = 0;
        while (Math.abs(diff) > 40 && changed && safety++ < 50) {
            changed = false;
            String[] order = diff > 0 ? incOrder : decOrder;
            for (String fnum : order) {
                String curLevel = factorLevels.get(fnum);
                if (curLevel == null) continue;
                String candidate = diff > 0 ? getNextHigherValidLevel(fnum, curLevel) : getNextLowerValidLevel(fnum, curLevel);
                if (candidate == null) continue;
                int oldPts = factorPoints.getOrDefault(fnum, getPointsForLevel(fnum, curLevel));
                int newPts = getPointsForLevel(fnum, preserveFactorLetter(curLevel, candidate));
                int change = newPts - oldPts;
                // Apply only if change moves closer to target and doesn't overshoot excessively
                if (diff > 0 && change > 0 || diff < 0 && change < 0) {
                    factorLevels.put(fnum, preserveFactorLetter(curLevel, candidate));
                    factorPoints.put(fnum, newPts);
                    totalPoints += change;
                    diff = targetMid - totalPoints;
                    changed = true;
                    if (Math.abs(diff) <= 40) break;
                }
            }
        }

        // Rebuild formatted evaluation deterministically using locked values and generated rationales
        StringBuilder deterministic = new StringBuilder();
        deterministic.append("STANDARD FACTOR EVALUATION\n\n");
        for (int i = 1; i <= 9; i++) {
            String f = String.valueOf(i);
            String lvl = factorLevels.getOrDefault(f, getDefaultFactorLevels(gsGrade).get(f));
            Integer pts = factorPoints.getOrDefault(f, getPointsForLevel(f, lvl));
            deterministic.append("Factor ").append(i).append(" – ").append(getFactorName(i))
                         .append(" Level ").append(lvl).append(", ").append(pts).append(" Points\n\n");
            // Use enhanced rationale that includes explicit adjacent-level comparisons
            deterministic.append(buildRationaleWithComparisons(i, lvl, duties)).append("\n\n");
        }
        deterministic.append(String.format("**Total Points: %d**\n", totalPoints));
        deterministic.append(String.format("**Final Grade: %s**\n", calculateFinalGrade(totalPoints)));
        deterministic.append(String.format("**Grade Range: %s**\n", calculateGradeRange(totalPoints)));

        formattedEval = deterministic.toString();
    }

    // If AI produced a formatted evaluation, augment each factor rationale to include "why not previous" and "why not next"
    formattedEval = augmentEvaluationRationales(formattedEval, factorLevels, duties);

    // Final enforcement: ensure summary reflects the chosen target grade range and grade
    Map<String, Integer> finalFactorPoints = extractFactorPoints(formattedEval);
    int finalTotal = finalFactorPoints.values().stream().mapToInt(Integer::intValue).sum();
    // If still outside, force total to midpoint and update summary (best-effort)
    if (finalTotal < targetMinPoints || finalTotal > targetMaxPoints) {
        finalTotal = (targetMinPoints + targetMaxPoints) / 2;
    }
    String finalGrade = gsGrade; // user requested always align with assigned grade
    String finalRange = getPointRangeForGrade(gsGrade);

    formattedEval = formattedEval.replaceAll("\\*\\*Total Points:\\s*\\d+\\*\\*", "**Total Points: " + finalTotal + "**");
    formattedEval = formattedEval.replaceAll("\\*\\*Final Grade:\\s*GS-\\d+\\*\\*", "**Final Grade: " + finalGrade + "**");
    formattedEval = formattedEval.replaceAll("\\*\\*Grade Range:\\s*[\\d\\-+]+\\*\\*", "**Grade Range: " + finalRange + "**");

    // Return the authoritative aligned evaluation
    writer.println("data: {\"evaluationStatement\":\"" + escapeJson(formattedEval) + "\"}\n");
    writer.flush();
}

private String buildRationaleWithComparisons(int factorNum, String level, String duties) {
    String base = generateSubstantiveRationale(factorNum, level, duties);
    String prev = getNextLowerValidLevel(String.valueOf(factorNum), level);
    String next = getNextHigherValidLevel(String.valueOf(factorNum), level);

    String dutiesSnippet = shortDutySnippet(duties);

    StringBuilder sb = new StringBuilder();
    // Start with the substantive rationale (ensure single punctuation)
    if (base != null && !base.trim().isEmpty()) {
        sb.append(base.trim());
        if (!base.trim().endsWith(".")) sb.append(".");
    } else {
        sb.append("Rationale: The assigned level reflects the duties and requirements described.");
    }

    // Explain why this level exceeds the previous level (if any)
    if (prev != null) {
        sb.append(" It exceeds the previous level (").append(prev).append(") because the duties, e.g. ")
          .append(dutiesSnippet)
          .append(", demonstrate greater ").append(inferContrastPhrase(factorNum, level, prev))
          .append(" than is implied by that lower level.");
    }

    // Explain why it does not meet the next higher level (if any)
    if (next != null) {
        sb.append(" It does not meet the next higher level (").append(next).append(") because that level requires ")
          .append(inferNextLevelRequirement(factorNum, next))
          .append(", which the duties do not demonstrate (for example: ").append(dutiesSnippet).append(").");
    }

    return sb.toString().trim();
}

private String shortDutySnippet(String duties) {
    if (duties == null || duties.isBlank()) return "the duties provided";
    // Remove percentage markers and collapse whitespace
    String cleaned = duties.replaceAll("\\d+%\\s*", "").replaceAll("\\s+", " ").trim();
    // Prefer the first clause/sentence up to 140 characters
    int cut = Math.min(cleaned.length(), 140);
    String snippet = cleaned.substring(0, cut);
    // Try to cut at a sentence boundary if reasonably soon
    int period = snippet.indexOf(". ");
    if (period > 40) snippet = snippet.substring(0, period + 1);
    if (snippet.length() > 120) snippet = snippet.substring(0, 117).trim() + "...";
    snippet = snippet.replace("\n", " ").replace("\"", "'");
    return snippet.isEmpty() ? "the duties provided" : "\"" + snippet + "\"";
}

private String augmentEvaluationRationales(String evaluation, Map<String, String> factorLevels, String duties) {
    if (evaluation == null || evaluation.isBlank() || factorLevels == null || factorLevels.isEmpty()) return evaluation;

    String result = evaluation;
    for (int i = 1; i <= 9; i++) {
        String factorNum = String.valueOf(i);
        String level = factorLevels.getOrDefault(factorNum, getDefaultFactorLevels("GS-12").get(factorNum));
        String newRationale = buildRationaleWithComparisons(i, level, duties);

        // Pattern matches either "**Factor X – Name Level 1-4, 225 Points**" or "Factor X – Name Level 1-4, 225 Points"
        // and captures the header as group(1) and the existing rationale block as group(2)
        String headerPattern = "(\\*{0,2}\\s*Factor\\s+" + i + "\\s[–\\-].*?Level\\s+\\d+-\\d+,\\s*\\d+\\s*Points\\s*)";
        String lookaheadForNext = (i < 9)
            ? "(?=(?:\\n\\n\\*{0,2}Factor\\s+" + (i+1) + "\\s)|\\n\\n\\*\\*Total Points:|\\z)"
            : "(?=\\n\\n\\*\\*Total Points:|\\z)";

        Pattern p = Pattern.compile(headerPattern + "(?s)(.*?)" + lookaheadForNext, Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(result);
        if (m.find()) {
            String header = m.group(1);
            String replacement = header + "\n" + newRationale + "\n\n";
            result = m.replaceFirst(Matcher.quoteReplacement(replacement));
        } else {
            // As a fallback, try to insert rationale after a simpler header match without duplicating content
            Pattern simpleHeader = Pattern.compile("(?i)(Factor\\s+" + i + "\\s[–\\-].*?Level\\s+\\d+-\\d+,\\s*\\d+\\s*Points)", Pattern.DOTALL);
            Matcher mh = simpleHeader.matcher(result);
            if (mh.find()) {
                int insertPos = mh.end();
                // Remove any immediate duplicated sentences if present (basic heuristic)
                String tail = result.substring(insertPos);
                // Trim up to the next blank line to avoid leaving old rationale
                int nextBlank = tail.indexOf("\n\n");
                if (nextBlank >= 0) {
                    String afterHeader = tail.substring(0, nextBlank);
                    // If afterHeader contains substantial text that looks like a rationale, remove it
                    if (afterHeader.length() < 1000) {
                        result = result.substring(0, insertPos) + "\n" + newRationale + "\n\n" + tail.substring(nextBlank + 2);
                    } else {
                        result = result.substring(0, insertPos) + "\n" + newRationale + "\n\n" + tail;
                    }
                } else {
                    result = result.substring(0, insertPos) + "\n" + newRationale + "\n\n" + tail;
                }
            }
        }
    }

    return result;
}

/**
 * Infer a short contrasting phrase to explain why current level exceeds previous.
 * Uses factor-specific cues.
 */
private String inferContrastPhrase(int factorNum, String currentLevel, String prevLevel) {
    switch (factorNum) {
        case 1: return "depth and breadth of professional knowledge";
        case 2: return "independence and decision-making authority";
        case 3: return "judgment in interpreting guidelines";
        case 4: return "complexity and scope of analytical work";
        case 5: return "scope and organizational impact";
        case 6: return "frequency and seniority of contacts";
        case 7: return "influence and purpose of interactions";
        case 8: return "physical demands or travel requirements";
        case 9: return "workplace hazards and environmental impact";
        default: return "responsibility and complexity";
    }
}

/**
 * Infer brief description of what the next higher level requires (used to explain why not next).
 */
private String inferNextLevelRequirement(int factorNum, String nextLevel) {
    switch (factorNum) {
        case 1: return "mastery or recognized subject-matter authority with broad program leadership responsibilities";
        case 2: return "administrative direction level responsibility and broad program control";
        case 3: return "minimal applicable guidance, requiring policy interpretation and precedent-setting";
        case 4: return "originating new techniques or establishing evaluative criteria for programs";
        case 5: return "organization-wide or agency-level impact affecting major program direction";
        case 6: return "regular contact with senior external officials and top-level stakeholders";
        case 7: return "negotiation and representation on highly significant or controversial matters";
        case 8: return "substantial physical effort or frequent field operations";
        case 9: return "work in hazardous, unique, or emergency conditions requiring special precautions";
        default: return "broader responsibilities consistent with the next level";
    }
}

/**
 * Enforce exact grade alignment by validating and correcting factor points
 */
private String enforceExactGradeAlignment(String response, String targetGrade, int minPoints, int maxPoints) {
    System.out.println("Enforcing exact grade alignment for " + targetGrade + " (" + minPoints + "-" + maxPoints + " points)");

    // Extract current factor levels and points
    Map<String, String> currentLevels = new HashMap<>();
    Map<String, Integer> currentPoints = new HashMap<>();
    Pattern factorPattern = Pattern.compile("Factor\\s+(\\d+)[^\\n]*?Level\\s+(\\d+-[\\dA-Z]),\\s*(\\d+)\\s*Points");
    Matcher matcher = factorPattern.matcher(response);

    int actualTotal = 0;
    while (matcher.find()) {
        String factorNum = matcher.group(1);
        String level = matcher.group(2);
        int points = Integer.parseInt(matcher.group(3));

        // Validate points match the level
        int correctPoints = getPointsForLevel(factorNum, level);
        if (correctPoints != points) {
            System.out.println("WARNING: Factor " + factorNum + " has wrong points: " + points + " (should be " + correctPoints + ")");
            points = correctPoints;
        }

        currentLevels.put(factorNum, level);
        currentPoints.put(factorNum, points);
        actualTotal += points;
    }

    System.out.println("Current total: " + actualTotal + " points (target: " + minPoints + "-" + maxPoints + ")");

    // If not in range, adjust factors to hit target
    if (actualTotal < minPoints || actualTotal > maxPoints) {
        System.out.println("FORCING grade alignment - adjusting factors...");

        int targetMidpoint = (minPoints + maxPoints) / 2;
        int pointsNeeded = targetMidpoint - actualTotal;

        // Adjust factors intelligently (prioritize Factor 1, 2, 5 for increases; 9, 8, 7 for decreases)
        String[] adjustOrder = pointsNeeded > 0 ?
            new String[]{"1", "5", "2", "4", "3", "6", "7", "8", "9"} :
            new String[]{"9", "8", "7", "6", "3", "4", "2", "5", "1"};

        for (String factorNum : adjustOrder) {
            if (Math.abs(pointsNeeded) < 50) break; // Close enough to target

            String currentLevel = currentLevels.get(factorNum);
            if (currentLevel == null) continue;

            String newLevel = pointsNeeded > 0 ?
                getNextHigherValidLevel(factorNum, currentLevel) :
                getNextLowerValidLevel(factorNum, currentLevel);

            if (newLevel != null) {
                int oldPoints = currentPoints.get(factorNum);
                int newPoints = getPointsForLevel(factorNum, newLevel);
                int change = newPoints - oldPoints;

                // Only apply if it moves us closer to target without overshooting too much
                if ((pointsNeeded > 0 && change > 0 && change <= pointsNeeded + 100) ||
                    (pointsNeeded < 0 && change < 0 && change >= pointsNeeded - 100)) {

                    // --- Use preserveFactorLetter here ---
                    Pattern headerPattern = Pattern.compile(
                        "(Factor\\s+" + factorNum + "[^\\n]*?)Level\\s+" + Pattern.quote(currentLevel) + ",\\s*" + oldPoints + "\\s*Points"
                    );
                    Matcher headerMatcher = headerPattern.matcher(response);
                    if (headerMatcher.find()) {
                        String replacement = headerMatcher.group(1) + "Level " + preserveFactorLetter(currentLevel, newLevel) + ", " + newPoints + " Points";
                        response = headerMatcher.replaceFirst(replacement);
                    }

                    currentLevels.put(factorNum, preserveFactorLetter(currentLevel, newLevel));
                    currentPoints.put(factorNum, newPoints);
                    actualTotal += change;
                    pointsNeeded -= change;

                    System.out.println("Adjusted Factor " + factorNum + ": " + currentLevel + " -> " +
                        preserveFactorLetter(currentLevel, newLevel) + " (" + (change > 0 ? "+" : "") + change + " pts)");
                }
            }
        }
    }

    // Recalculate final total
    int finalTotal = currentPoints.values().stream().mapToInt(Integer::intValue).sum();
    String calculatedGrade = calculateFinalGrade(finalTotal);
    String gradeRange = calculateGradeRange(finalTotal);

    // Force update the summary section with correct values
    response = response.replaceAll("\\*\\*Total Points:\\s*\\d+\\*\\*", "**Total Points: " + finalTotal + "**");
    response = response.replaceAll("\\*\\*Final Grade:\\s*GS-\\d+\\*\\*", "**Final Grade: " + calculatedGrade + "**");
    response = response.replaceAll("\\*\\*Grade Range:\\s*[\\d-]+\\+?\\*\\*", "**Grade Range: " + gradeRange + "**");

    // Final validation
    if (!calculatedGrade.equals(targetGrade)) {
        System.out.println("WARNING: Grade mismatch after adjustment - Calculated: " + calculatedGrade +
            ", Target: " + targetGrade + " (Total: " + finalTotal + " points)");
    } else {
        System.out.println("SUCCESS: Grade alignment achieved - " + finalTotal + " points = " + calculatedGrade);
    }

    return response;
}

// NEW: Get minimum factor levels required for each grade
private Map<String, String> getMinimumFactorLevelsForGrade(String grade) {
    switch (grade.toUpperCase()) {
        case "GS-15":
            return Map.of("f1", "1-9", "f2", "2-5", "f5", "5-6"); // Expert level
        case "GS-14":
            return Map.of("f1", "1-8", "f2", "2-4", "f5", "5-5"); // Advanced professional
        case "GS-13":
            return Map.of("f1", "1-7", "f2", "2-4", "f5", "5-4"); // Senior professional
        case "GS-12":
            return Map.of("f1", "1-6", "f2", "2-4", "f5", "5-4"); // Program responsibility
        case "GS-11":
            return Map.of("f1", "1-5", "f2", "2-3", "f5", "5-3"); // Full professional
        case "GS-9":
            return Map.of("f1", "1-4", "f2", "2-2", "f5", "5-2"); // Developmental
        case "GS-7":
            return Map.of("f1", "1-3", "f2", "2-2", "f5", "5-2"); // Entry professional
        default:
            return Map.of("f1", "1-6", "f2", "2-4", "f5", "5-4"); // Default to GS-12
    }
}

/**
 * Returns the OPM point value for a given factor number and level string.
 */
private int getPointsForLevel(String factorNum, String level) {
    switch (factorNum) {
        case "1":
            switch (level) {
                case "1-1": return 50;
                case "1-2": return 200;
                case "1-3": return 350;
                case "1-4": return 550;
                case "1-5": return 750;
                case "1-6": return 950;
                case "1-7": return 1250;
                case "1-8": return 1550;
                case "1-9": return 1850;
            }
            break;
        case "2":
            switch (level) {
                case "2-1": return 25;
                case "2-2": return 125;
                case "2-3": return 275;
                case "2-4": return 450;
                case "2-5": return 650;
            }
            break;
        case "3":
            switch (level) {
                case "3-1": return 25;
                case "3-2": return 125;
                case "3-3": return 275;
                case "3-4": return 450;
                case "3-5": return 650;
            }
            break;
        case "4":
            switch (level) {
                case "4-1": return 25;
                case "4-2": return 75;
                case "4-3": return 150;
                case "4-4": return 225;
                case "4-5": return 325;
                case "4-6": return 450;
            }
            break;
        case "5":
            switch (level) {
                case "5-1": return 25;
                case "5-2": return 75;
                case "5-3": return 150;
                case "5-4": return 225;
                case "5-5": return 325;
                case "5-6": return 450;
            }
            break;
        case "6":
            switch (level) {
                case "6-1": return 10;
                case "6-2": return 25;
                case "6-3": return 60;
                case "6-4": return 110;
            }
            break;
        case "7":
            switch (level) {
                case "7-1": return 20;
                case "7-2": return 50;
                case "7-3": return 120;
                case "7-4": return 220;
            }
            break;
        case "8":
            switch (level) {
                case "8-1": return 5;
                case "8-2": return 20;
                case "8-3": return 50;
            }
            break;
        case "9":
            switch (level) {
                case "9-1": return 5;
                case "9-2": return 20;
                case "9-3": return 50;
            }
            break;
    }
    // Default fallback if not found
    return 0;
}

/**
 * Extract existing factor structure from payload.
 */
private Map<String, String> extractExistingFactorStructure(Map<String, Object> payload) {
    Map<String, String> factors = new LinkedHashMap<>();
    
    // Try to get factors from multiple possible keys
    Object factorsObj = payload.get("factors");
    if (factorsObj instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> factorsMap = (Map<String, Object>) factorsObj;
        
        factorsMap.forEach((key, value) -> {
            if (key.matches("(?i)Factor\\s*\\d+[A-Za-z]?")) {
                if (value instanceof String) {
                    factors.put(key, (String) value);
                } else if (value instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> valueMap = (Map<String, Object>) value;
                    String content = (String) valueMap.getOrDefault("content", 
                                    valueMap.getOrDefault("rationale", ""));
                    factors.put(key, content);
                }
            }
        });
    }
    
    // Also check for factors at top level of payload
    payload.forEach((key, value) -> {
        if (key.matches("(?i)Factor\\s*\\d+[A-Za-z]?") && !factors.containsKey(key)) {
            if (value instanceof String) {
                factors.put(key, (String) value);
            }
        }
    });
    
    return factors;
}

private Map<String, Object> extractCurrentFactorData(Map<String, Object> payload) {
    Map<String, Object> current = new LinkedHashMap<>();
    
    if (payload == null) {
        System.out.println("WARNING: extractCurrentFactorData received null payload");
        return current;
    }
    
    try {
        Object factorsObj = payload.get("factors");
        if (factorsObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> factorsMap = (Map<String, Object>) factorsObj;
            current.putAll(factorsMap);
            System.out.println("Extracted " + current.size() + " factors from payload.factors");
        } else {
            System.out.println("No factors map found in payload, checking top-level keys");
            // Also check for factor keys at top level
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                if (entry.getKey() != null && entry.getKey().startsWith("Factor ")) {
                    current.put(entry.getKey(), entry.getValue());
                }
            }
            System.out.println("Found " + current.size() + " factor keys at top level");
        }
    } catch (Exception e) {
        System.err.println("Error extracting current factor data: " + e.getMessage());
        e.printStackTrace();
    }
    
    return current;
}

/**
 * Extract level from AI factor response.
 */
private String extractLevelFromAIFactor(Object factorObj, String factorId) {
    if (factorObj == null) return null;
    
    if (factorObj instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) factorObj;
        Object level = map.get("level");
        return level != null ? String.valueOf(level) : null;
    }
    
    return null;
}

/**
 * Extract rationale from AI factor response.
 */
private String extractRationaleFromAIFactor(Object factorObj) {
    if (factorObj == null) return "";
    
    if (factorObj instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) factorObj;
        Object rationale = map.get("rationale");
        if (rationale != null) return String.valueOf(rationale);
        
        Object content = map.get("content");
        if (content != null) return String.valueOf(content);
    }
    
    return "";
}

/**
 * Build locked factor header (preserves factor name, only updates level/points).
 */
private String buildLockedFactorHeader(String factorId, String level, int points, boolean isSupervisory) {
    String factorName;
    
    if (isSupervisory) {
        // GSSG factor names
        switch (factorId.toUpperCase()) {
            case "1": factorName = "Program Scope and Effect"; break;
            case "2": factorName = "Organizational Setting"; break;
            case "3": factorName = "Supervisory & Managerial Authority Exercised"; break;
            case "4A": factorName = "Personal Contacts (Nature of Contacts)"; break;
            case "4B": factorName = "Personal Contacts (Purpose of Contacts)"; break;
            case "5": factorName = "Difficulty of Typical Work Directed"; break;
            case "6": factorName = "Other Conditions"; break;
            default: factorName = "Factor " + factorId; break;
        }
    } else {
        // FES factor names
        factorName = getFactorName(factorId.replaceAll("[^0-9]", ""));
    }
    
    return String.format("Factor %s - %s Level %s, %d Points", factorId, factorName, level, points);
}

/**
 * Get factor name for FES factors.
 */
private String getFactorName(String factorNum) {
    switch (factorNum) {
        case "1": return "Knowledge Required by the Position";
        case "2": return "Supervisory Controls";
        case "3": return "Guidelines";
        case "4": return "Complexity";
        case "5": return "Scope and Effect";
        case "6": return "Personal Contacts";
        case "7": return "Purpose of Contacts";
        case "8": return "Physical Demands";
        case "9": return "Work Environment";
        default: return "Factor " + factorNum;
    }
}

/**
 * Validate and normalize FES factor levels (non-supervisory)
 */
private String normalizeAndValidateFesLevel(String factorId, String level) {
    if (level == null || level.trim().isEmpty()) {
        return factorId + "-1"; // Default to level 1
    }
    
    level = level.trim();
    
    // Expected ranges for FES factors
    Map<String, Integer> maxLevels = Map.of(
        "1", 9,    // Knowledge: 1-1 to 1-9
        "2", 5,    // Supervisory Controls: 2-1 to 2-5
        "3", 5,    // Guidelines: 3-1 to 3-5
        "4", 6,    // Complexity: 4-1 to 4-6
        "5", 6,    // Scope and Effect: 5-1 to 5-6
        "6", 4,    // Personal Contacts: 6-1 to 6-4
        "7", 4,    // Purpose of Contacts: 7-1 to 7-4
        "8", 3,    // Physical Demands: 8-1 to 8-3
        "9", 3     // Work Environment: 9-1 to 9-3
    );
    
    // Parse level
    String[] parts = level.split("-");
    if (parts.length != 2) {
        try {
            int num = Integer.parseInt(level);
            level = factorId + "-" + num;
            parts = level.split("-");
        } catch (NumberFormatException e) {
            return factorId + "-1";
        }
    }
    
    try {
        String prefix = parts[0];
        int levelNum = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
        
        // Validate prefix matches expected factor
        if (!prefix.equals(factorId)) {
            System.out.println("WARNING: Factor mismatch - got " + level + " for factor " + factorId + 
                             ". Correcting prefix.");
            prefix = factorId;
        }
        
        // Validate level is in range
        Integer maxLevel = maxLevels.get(factorId);
        if (maxLevel != null && levelNum > maxLevel) {
            System.out.println("WARNING: Level " + levelNum + " exceeds max " + maxLevel + 
                             " for factor " + factorId + ". Capping.");
            levelNum = maxLevel;
        }
        if (levelNum < 1) levelNum = 1;
        
        return prefix + "-" + levelNum;
    } catch (Exception e) {
        System.err.println("Error parsing FES level " + level + " for factor " + factorId);
        return factorId + "-1";
    }
}

/**
 * Adjust GSSG total while preserving locked titles
 */
private int adjustGSSGToGradeRangeLocked(Map<String, Object> factors, int currentTotal, 
                                         int minPoints, int maxPoints) {
    if (currentTotal >= minPoints && currentTotal <= maxPoints) {
        return currentTotal;
    }
    
    int target = (minPoints + maxPoints) / 2;
    int diff = target - currentTotal;
    
    String[] adjustOrder = diff > 0 ? 
        new String[]{"1", "5", "3", "2", "4A", "4B", "6"} :
        new String[]{"6", "4B", "4A", "2", "3", "5", "1"};
    
    boolean changed = true;
    int iterations = 0;
    
    while (Math.abs(diff) > 25 && changed && iterations++ < 50) {
        changed = false;
        
        for (String factorId : adjustOrder) {
            String key = "Factor " + factorId;
            @SuppressWarnings("unchecked")
            Map<String, Object> factor = (Map<String, Object>) factors.get(key);
            if (factor == null) continue;
            
            String currentLevel = (String) factor.get("level");
            String newLevel = diff > 0 ?
                getNextHigherValidGssgLevel(factorId, currentLevel) :
                getNextLowerValidGssgLevel(factorId, currentLevel);
            
            if (newLevel != null) {
                int oldPts = (Integer) factor.get("points");
                int newPts = getGssgPointsForLevel(factorId, newLevel);
                int change = newPts - oldPts;
                
                if ((diff > 0 && change > 0) || (diff < 0 && change < 0)) {
                    factor.put("level", newLevel);
                    factor.put("points", newPts);
                    // Rebuild header with LOCKED title, only level/points change
                    factor.put("header", buildLockedFactorHeader(factorId, newLevel, newPts, true));
                    currentTotal += change;
                    diff = target - currentTotal;
                    changed = true;
                    
                    if (Math.abs(diff) <= 25) break;
                }
            }
        }
    }
    
    return currentTotal;
}

@PostMapping("/regenerate-factors")
public Map<String, Object> regenerateFactors(@RequestBody Map<String, Object> payload) {
    try {
        System.out.println("=== REGENERATE FACTORS REQUEST ===");
        System.out.println("Payload keys: " + (payload != null ? payload.keySet() : "NULL PAYLOAD"));
        
        if (payload == null) {
            return Map.of("success", false, "error", "Payload is null");
        }

        String duties = extractDutiesFromPayload(payload);
        if (duties == null || duties.trim().isEmpty()) {
            return Map.of("success", false, "error", "No duties found in payload");
        }
        
        System.out.println("Extracted duties length: " + duties.length());

        Map<String, String> existingFactors = extractExistingFactorStructure(payload);
        System.out.println("Existing factors found: " + existingFactors.size());
        
        boolean isSupervisory = determineLOCKEDSystem(existingFactors);

        String clientSystemLock = (String) payload.get("systemLocked");
        if (clientSystemLock != null && !clientSystemLock.trim().isEmpty()) {
            boolean clientIsSupervisory = "GSSG".equalsIgnoreCase(clientSystemLock.trim());
            if (clientIsSupervisory != isSupervisory) {
                System.out.println("WARNING: Client system lock differs. Client: " + clientSystemLock + 
                                 ", Detected: " + (isSupervisory ? "GSSG" : "FES"));
                isSupervisory = clientIsSupervisory;
            }
        }
        
        System.out.println("System determined: " + (isSupervisory ? "GSSG SUPERVISORY" : "FES NON-SUPERVISORY"));

        String targetGrade = String.valueOf(payload.getOrDefault("expectedGrade",
                             payload.getOrDefault("gsGrade", "GS-13")));
        String supervisoryLevel = String.valueOf(payload.getOrDefault("supervisoryLevel",
            isSupervisory ? "Supervisor" : "Non-Supervisory"));

        Map<String, Object> out;
        if (isSupervisory) {
            out = regenerateGSSGFactors(existingFactors, duties, targetGrade, supervisoryLevel);
        } else {
            out = regenerateFESFactors(existingFactors, duties, targetGrade);
        }

        // ENSURE summary fields are present and correct
        return ensureSummaryFields(out, isSupervisory);

    } catch (Exception ex) {
        System.err.println("EXCEPTION in regenerateFactors: " + ex.getMessage());
        ex.printStackTrace();
        return Map.of("success", false, "error", "Failed to regenerate factors: " + 
                     (ex.getMessage() != null ? ex.getMessage() : "Unknown error"));
    }
}

/**
 * NEW: Regenerate GSSG factors with LOCKED system
 */
private Map<String, Object> regenerateGSSGFactors(Map<String, String> existingFactors, 
                                                   String duties, String targetGrade,
                                                   String supervisoryLevel) throws Exception {
    System.out.println("Regenerating GSSG supervisory factors with LOCKED titles for grade: " + targetGrade);
    
    int minPoints = getMinPointsForGrade(targetGrade);
    int maxPoints = getMaxPointsForGrade(targetGrade);
    int targetMid = (minPoints + maxPoints) / 2;
    
    String pdfContext = getAutoPdfContext();
    
    // AI prompt that explicitly requests GSSG factors
    String prompt = pdfContext + String.format("""
        You are an OPM classification expert using the GSSG (General Schedule Supervisory Guide) 
        6-factor system for supervisory positions ONLY.
        
        Target Grade: %s (%d-%d points)
        Supervisory Level: %s
        
        CRITICAL: Analyze these SUPERVISORY duties and assign appropriate levels for ALL 7 GSSG factors:
        - Factor 1: Program Scope and Effect (1-1 to 1-5)
        - Factor 2: Organizational Setting (2-1 to 2-3)
        - Factor 3: Supervisory & Managerial Authority (3-1 to 3-4)
        - Factor 4A: Personal Contacts - Nature (4A-1 to 4A-4)
        - Factor 4B: Personal Contacts - Purpose (4B-1 to 4B-4)
        - Factor 5: Difficulty of Work Directed (5-1 to 5-8)
        - Factor 6: Other Conditions (6-1 to 6-6)
        
        Focus on SUPERVISORY responsibilities: program oversight, staff management, 
        administrative authority, and organizational impact.
        
        DO NOT use FES factors (Knowledge Required, Complexity, etc.).
        
        Respond ONLY in JSON format:
        {
          "Factor 1": {"level": "1-X", "rationale": "program scope justification"},
          "Factor 2": {"level": "2-X", "rationale": "organizational setting justification"},
          "Factor 3": {"level": "3-X", "rationale": "supervisory authority justification"},
          "Factor 4A": {"level": "4A-X", "rationale": "nature of contacts justification"},
          "Factor 4B": {"level": "4B-X", "rationale": "purpose of contacts justification"},
          "Factor 5": {"level": "5-X", "rationale": "work directed justification"},
          "Factor 6": {"level": "6-X", "rationale": "other conditions justification"}
        }
        
        Supervisory Duties:
        %s
        """, targetGrade, minPoints, maxPoints, supervisoryLevel, duties);
    
    String aiResponse = callOpenAIWithTimeout(prompt, 60);
    String jsonResponse = extractJsonFromResponse(aiResponse);
    
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> aiFactors;
    try {
        aiFactors = mapper.readValue(jsonResponse, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
        System.err.println("AI parse failed, using intelligent defaults");
        aiFactors = new HashMap<>();
    }
    
    // Build validated factors with LOCKED GSSG titles
    Map<String, Object> validatedFactors = new LinkedHashMap<>();
    int totalPoints = 0;
    
    String[] factorOrder = {"1", "2", "3", "4A", "4B", "5", "6"};
    for (String factorId : factorOrder) {
        String key = "Factor " + factorId;
        
        // Get AI level or use intelligent default
        String level = extractLevelFromAIFactor(aiFactors.get(key), factorId);
        int fidNum = Integer.parseInt(factorId.replaceAll("[^0-9]", ""));
        if (level == null) {
            level = getIntelligentGssgLevel(fidNum, duties, targetGrade);
        }
        
        level = normalizeGssgLevel(factorId, level);
        int points = getGssgPointsForLevel(factorId, level);
        
        String rationale = extractRationaleFromAIFactor(aiFactors.get(key));
        if (rationale == null || rationale.trim().isEmpty()) {
            rationale = buildGssgRationale(fidNum, level, duties, 
                                          factorId.contains("A") || factorId.contains("B") ? factorId : null);
        }
        
        Map<String, Object> factorData = new LinkedHashMap<>();
        factorData.put("header", buildLockedFactorHeader(factorId, level, points, true));
        factorData.put("level", level);
        factorData.put("points", points);
        factorData.put("rationale", rationale);
        
        validatedFactors.put(key, factorData);
        totalPoints += points;
        
        System.out.println("  " + buildLockedFactorHeader(factorId, level, points, true));
    }
    
    // Adjust to grade range if needed
    totalPoints = adjustGSSGToGradeRangeLocked(validatedFactors, totalPoints, minPoints, maxPoints);
    
    String gradeRange = getPointRangeForGrade(targetGrade);
    
    System.out.println("GSSG regeneration complete: " + totalPoints + " pts = " + targetGrade);
    
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("success", true);
    result.put("factors", validatedFactors);
    result.put("totalPoints", totalPoints);
    result.put("finalGrade", targetGrade);
    result.put("gradeRange", gradeRange);
    result.put("supervisoryEvaluation", true);
    result.put("systemLocked", "GSSG");
    return result;
}

/**
 * NEW: Regenerate FES factors with LOCKED system
 */
private Map<String, Object> regenerateFESFactors(Map<String, String> existingFactors,
                                                  String duties, String targetGrade) throws Exception {
    System.out.println("Regenerating FES non-supervisory factors with LOCKED titles for grade: " + targetGrade);
    
    String pdfContext = getAutoPdfContext();
    
    String prompt = pdfContext + String.format("""
        You are an OPM classification expert using the FES (Factor Evaluation System) 
        9-factor non-supervisory system ONLY.
        
        Target Grade: %s
        
        CRITICAL: Analyze these NON-SUPERVISORY duties and assign appropriate levels for ALL 9 FES factors:
        - Factor 1: Knowledge Required by the Position (1-1 to 1-9)
        - Factor 2: Supervisory Controls (2-1 to 2-5)
        - Factor 3: Guidelines (3-1 to 3-5)
        - Factor 4: Complexity (4-1 to 4-6)
        - Factor 5: Scope and Effect (5-1 to 5-6)
        - Factor 6: Personal Contacts (6-1 to 6-4)
        - Factor 7: Purpose of Contacts (7-1 to 7-4)
        - Factor 8: Physical Demands (8-1 to 8-3)
        - Factor 9: Work Environment (9-1 to 9-3)
        
        DO NOT use GSSG factors (Program Scope, Organizational Setting, etc.).
        
        Respond ONLY in JSON format:
        {
          "Factor 1": {"level": "1-X", "rationale": "knowledge justification"},
          ... (through Factor 9)
        }
        
        Non-Supervisory Duties:
        %s
        """, targetGrade, duties);
    
    String aiResponse = callOpenAIWithTimeout(prompt, 60);
    String jsonResponse = extractJsonFromResponse(aiResponse);
    
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> aiFactors;
    try {
        aiFactors = mapper.readValue(jsonResponse, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
        System.err.println("AI parse failed, using defaults");
        aiFactors = new HashMap<>();
    }
    
    Map<String, Object> validatedFactors = new LinkedHashMap<>();
    int totalPoints = 0;
    
    for (int i = 1; i <= 9; i++) {
        String factorId = String.valueOf(i);
        String key = "Factor " + i;
        
        String level = extractLevelFromAIFactor(aiFactors.get(key), factorId);
        if (level == null) {
            level = getIntelligentLevel(i, duties);
        }
        
        level = normalizeAndValidateFesLevel(factorId, level);
        int points = getPointsForLevel(factorId, level);
        
        String rationale = extractRationaleFromAIFactor(aiFactors.get(key));
        if (rationale == null || rationale.trim().isEmpty()) {
            rationale = generateSubstantiveRationale(i, level, duties);
        }
        
        Map<String, Object> factorData = new LinkedHashMap<>();
        factorData.put("header", buildLockedFactorHeader(factorId, level, points, false));
        factorData.put("level", level);
        factorData.put("points", points);
        factorData.put("rationale", rationale);
        
        validatedFactors.put(key, factorData);
        totalPoints += points;
        
        System.out.println("  " + buildLockedFactorHeader(factorId, level, points, false));
    }
    
    totalPoints = avoidForbiddenGradeRanges(totalPoints, validatedFactors);
    
    String finalGrade = calculateFinalGrade(totalPoints);
    String gradeRange = calculateGradeRange(totalPoints);
    
    System.out.println("FES regeneration complete: " + totalPoints + " pts = " + finalGrade);
    
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("success", true);
    result.put("factors", validatedFactors);
    result.put("totalPoints", totalPoints);
    result.put("finalGrade", finalGrade);
    result.put("gradeRange", gradeRange);
    result.put("supervisoryEvaluation", false);
    result.put("systemLocked", "FES");
    return result;
}

private boolean determineLOCKEDSystem(Map<String, String> existingFactors) {
    String combined = String.join(" ", existingFactors.values()).toUpperCase();
    
    // GSSG supervisory indicators (check titles in values)
    if (combined.contains("PROGRAM SCOPE AND EFFECT") ||
        combined.contains("ORGANIZATIONAL SETTING") ||
        combined.contains("SUPERVISORY & MANAGERIAL AUTHORITY") ||
        combined.contains("DIFFICULTY OF TYPICAL WORK DIRECTED")) {
        System.out.println("LOCKED: GSSG (found supervisory titles in content)");
        return true;
    }
    
    // FES non-supervisory indicators
    if (combined.contains("KNOWLEDGE REQUIRED BY THE POSITION") ||
        combined.contains("PURPOSE OF CONTACTS") ||
        combined.contains("PHYSICAL DEMANDS") ||
        combined.contains("WORK ENVIRONMENT")) {
        System.out.println("LOCKED: FES (found non-supervisory titles in content)");
        return false;
    }
    
    // Check for 4A/4B keys as definitive indicator
    if (existingFactors.keySet().stream().anyMatch(k -> k.contains("4A") || k.contains("4B"))) {
        System.out.println("LOCKED: GSSG (found Factor 4A/4B)");
        return true;
    }
    
    // Check for Factors 7/8/9 as definitive FES indicator
    if (existingFactors.keySet().stream().anyMatch(k -> 
        k.matches(".*Factor\\s*[789]\\b.*"))) {
        System.out.println("LOCKED: FES (found Factors 7/8/9)");
        return false;
    }
    
    System.out.println("LOCKED: Defaulting to FES");
    return false;
}

private String extractDutiesFromPayload(Map<String, Object> payload) {
    if (payload == null) return "";
    
    try {
        // Try direct duties field first
        Object dutiesObj = payload.get("duties");
        if (dutiesObj != null) {
            String d = String.valueOf(dutiesObj).trim();
            if (!d.isEmpty() && !d.equals("null")) return d;
        }

        // Try nested maps or JSON strings
        for (String key : List.of("pdSections", "currentSections", "existingSections", "sections")) {
            Object obj = payload.get(key);
            if (obj instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                String md = combineMajorDuties((Map<String, Object>) m);
                if (md != null && !md.isEmpty()) return md;
            } else if (obj instanceof String s && s != null) {
                String json = s.trim();
                if ((json.startsWith("{") && json.endsWith("}")) || (json.startsWith("[") && json.endsWith("]"))) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = new ObjectMapper().readValue(json, new TypeReference<Map<String, Object>>() {});
                        String md = combineMajorDuties(parsed);
                        if (md != null && !md.isEmpty()) return md;
                    } catch (Exception ignore) {}
                }
            }
        }

        // As a last resort, scan top-level
        String combined = combineMajorDuties(payload);
        return combined != null ? combined : "";
    } catch (Exception e) {
        System.err.println("Error extracting duties: " + e.getMessage());
        return "";
    }
}

private String combineMajorDuties(Map<String, Object> sections) {
    if (sections == null || sections.isEmpty()) return "";
    
    try {
        // Prefer numbered duties
        List<Map.Entry<String, Object>> numbered = sections.entrySet().stream()
            .filter(e -> e.getKey() != null && e.getValue() != null && 
                        e.getKey().trim().matches("(?i)^MAJOR DUTY\\s*\\d+$"))
            .sorted((a,b) -> {
                try {
                    int na = Integer.parseInt(a.getKey().replaceAll("\\D+", ""));
                    int nb = Integer.parseInt(b.getKey().replaceAll("\\D+", ""));
                    return Integer.compare(na, nb);
                } catch (Exception e) {
                    return 0;
                }
            })
            .toList();
            
        if (!numbered.isEmpty()) {
            String combined = numbered.stream()
                .map(e -> String.valueOf(e.getValue()))
                .filter(s -> s != null && !s.equals("null") && !s.isEmpty())
                .collect(Collectors.joining("\n"));
            if (!combined.isEmpty()) return combined;
        }
        
        // Fallback to single "MAJOR DUTIES"
        for (Map.Entry<String, Object> e : sections.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && 
                e.getKey().trim().equalsIgnoreCase("MAJOR DUTIES")) {
                String val = String.valueOf(e.getValue());
                if (!val.equals("null") && !val.isEmpty()) return val;
            }
        }
    } catch (Exception e) {
        System.err.println("Error combining duties: " + e.getMessage());
    }
    
    return "";
}

@PostMapping("/update-factor-points")
public Map<String, Object> updateFactorPoints(@RequestBody Map<String, Object> payload) {
    try {
        System.out.println("=== UPDATE FACTOR POINTS - SYSTEM LOCKED ===");
        System.out.println("Payload keys: " + (payload != null ? payload.keySet() : "NULL PAYLOAD"));

        if (payload == null) {
            return Map.of("success", false, "error", "Payload is null");
        }

        // NEW: Check if AI re-analysis is requested
        Boolean requestAIReanalysis = (Boolean) payload.get("requestAIReanalysis");
        String editedFactorId = (String) payload.get("editedFactorId");
        
        Map<String, String> existingFactors = extractExistingFactorStructure(payload);
        System.out.println("Existing factors count: " + existingFactors.size());
        
        boolean isSupervisory = determineLOCKEDSystem(existingFactors);

        String systemLocked = (String) payload.get("systemLocked");
        if (systemLocked != null && !systemLocked.trim().isEmpty()) {
            boolean clientSystem = "GSSG".equalsIgnoreCase(systemLocked.trim());
            if (clientSystem != isSupervisory) {
                System.out.println("Client system lock differs; honoring client lock: " + systemLocked);
                isSupervisory = clientSystem;
            }
        }
        
        System.out.println("System: " + (isSupervisory ? "GSSG SUPERVISORY" : "FES NON-SUPERVISORY"));

        String duties = extractDutiesFromPayload(payload);
        if (duties == null || duties.trim().isEmpty()) {
            System.out.println("WARNING: No duties found, using empty string");
            duties = "";
        }
        
        String targetGrade = String.valueOf(payload.getOrDefault("expectedGrade",
                payload.getOrDefault("gsGrade", "GS-13")));

        // NEW: Handle AI re-analysis request for edited factor
        if (requestAIReanalysis != null && requestAIReanalysis && editedFactorId != null) {
            System.out.println("=== AI RE-ANALYSIS REQUESTED ===");
            System.out.println("Factor ID: " + editedFactorId);
            
            try {
                // Get the updated content for this factor
                @SuppressWarnings("unchecked")
                Map<String, Object> factors = (Map<String, Object>) payload.get("factors");
                
                if (factors != null) {
                    String factorKey = "Factor " + editedFactorId;
                    
                    if (factors.containsKey(factorKey)) {
                        Object factorData = factors.get(factorKey);
                        String updatedRationale = extractRationaleFromAIFactor(factorData);
                        
                        System.out.println("Updated rationale length: " + 
                                         (updatedRationale != null ? updatedRationale.length() : 0));
                        
                        // Call AI to determine appropriate level/points based on updated content
                        String newLevel = determineFactorLevelFromAI(editedFactorId, updatedRationale, 
                                                                     duties, isSupervisory);
                        int newPoints = isSupervisory ? 
                            getGssgPointsForLevel(editedFactorId, newLevel) :
                            getPointsForLevel(editedFactorId, newLevel);
                        
                        // Update the factor with AI-determined values
                        Map<String, Object> updatedFactor = new LinkedHashMap<>();
                        updatedFactor.put("level", newLevel);
                        updatedFactor.put("points", newPoints);
                        updatedFactor.put("rationale", updatedRationale);
                        updatedFactor.put("header", buildLockedFactorHeader(editedFactorId, newLevel, 
                                                                           newPoints, isSupervisory));
                        
                        factors.put(factorKey, updatedFactor);
                        
                        System.out.println("AI re-evaluated Factor " + editedFactorId + 
                                         " -> Level " + newLevel + ", " + newPoints + " points");
                        
                        // Update payload with new factor data
                        payload.put("factors", factors);
                    } else {
                        System.out.println("WARNING: Factor " + factorKey + " not found in factors map");
                    }
                } else {
                    System.out.println("WARNING: No factors map found in payload");
                }
            } catch (Exception aiError) {
                System.err.println("AI re-analysis failed: " + aiError.getMessage());
                aiError.printStackTrace();
                // Continue with normal update even if AI re-analysis fails
            }
        }

        // Continue with existing update logic
        Map<String, Object> out;
        if (isSupervisory) {
            out = updateGSSGFactorPointsLocked(existingFactors, payload, duties, targetGrade, 
                                              editedFactorId != null ? "Factor " + editedFactorId : null);
        } else {
            out = updateFESFactorPointsLocked(existingFactors, payload, duties, targetGrade, 
                                             editedFactorId != null ? "Factor " + editedFactorId : null);
        }

        // ENSURE summary fields are present and correct
        return ensureSummaryFields(out, isSupervisory);

    } catch (Exception ex) {
        System.err.println("EXCEPTION in updateFactorPoints: " + ex.getMessage());
        ex.printStackTrace();
        return Map.of("success", false, "error", 
                     "Failed to update factor points: " + 
                     (ex.getMessage() != null ? ex.getMessage() : "Unknown error"));
    }
}

private String determineFactorLevelFromAI(String factorId, String rationale, String duties, boolean isSupervisory) throws Exception {
    String pdfContext = getAutoPdfContext();
    
    String prompt = pdfContext + String.format("""
        You are an OPM classification expert. Based on the updated rationale below, 
        determine the most appropriate level for Factor %s in a %s position.
        
        Factor %s Updated Rationale:
        %s
        
        Position Duties:
        %s
        
        Respond with ONLY the factor level (e.g., "1-7" or "4A-3"). No explanation.
        """, 
        factorId, 
        isSupervisory ? "GSSG supervisory" : "FES non-supervisory",
        factorId,
        rationale,
        duties);
    
    String response = callOpenAIWithTimeout(prompt, 15);
    
    // Extract level from response
    Pattern levelPattern = Pattern.compile("(\\d+[A-Za-z]?-\\d+)");
    Matcher matcher = levelPattern.matcher(response);
    
    if (matcher.find()) {
        String level = matcher.group(1);
        return isSupervisory ? 
            normalizeGssgLevel(factorId, level) :
            normalizeAndValidateFesLevel(factorId, level);
    }
    
    // Fallback to intelligent default
    return isSupervisory ? 
        getIntelligentGssgLevel(Integer.parseInt(factorId.replaceAll("[^0-9]", "")), duties, "GS-13") :
        getIntelligentLevel(Integer.parseInt(factorId.replaceAll("[^0-9]", "")), duties);
}

/**
 * Extract specific factor with better null handling (preserve existing content as rationale)
 */
private Map<String, Object> extractFactorFromCurrent(Map<String, Object> currentFactors, String factorKey) {
    if (currentFactors == null || factorKey == null) return null;

    Object factorObj = currentFactors.get(factorKey);

    if (factorObj instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> factorMap = (Map<String, Object>) factorObj;
        return factorMap;
    }

    if (factorObj instanceof String) {
        String factorStr = (String) factorObj;
        Map<String, Object> parsed = new LinkedHashMap<>();

        // Preserve existing content as rationale (even if level/points not in content)
        String trimmed = factorStr.trim();
        if (!trimmed.isEmpty()) parsed.put("rationale", trimmed);

        // Try to extract level/points if present
        Pattern levelPattern = Pattern.compile("Level\\s+(\\d+[AB]?-\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher levelMatcher = levelPattern.matcher(factorStr);
        if (levelMatcher.find()) {
            parsed.put("level", levelMatcher.group(1));
        }

        Pattern pointsPattern = Pattern.compile("(\\d+)\\s+Points", Pattern.CASE_INSENSITIVE);
        Matcher pointsMatcher = pointsPattern.matcher(factorStr);
        if (pointsMatcher.find()) {
            try {
                parsed.put("points", Integer.parseInt(pointsMatcher.group(1)));
            } catch (NumberFormatException ignore) {}
        }

        return parsed.isEmpty() ? Map.of("rationale", "") : parsed;
    }

    return null;
}

/**
 * Update GSSG factors with LOCKED titles
 */
private Map<String, Object> updateGSSGFactorPointsLocked(Map<String, String> existingFactors,
                                                          Map<String, Object> payload,
                                                          String duties, String targetGrade,
                                                          String updatedFactorKey) throws Exception {
    System.out.println("=== UPDATE GSSG FACTORS (LOCKED TITLES) ===");
    System.out.println("Target grade: " + targetGrade);
    System.out.println("Existing factors count: " + existingFactors.size());
    System.out.println("Duties length: " + (duties != null ? duties.length() : 0));
    System.out.println("Payload keys: " + payload.keySet());
    
    try {
        int minPoints = getMinPointsForGrade(targetGrade);
        int maxPoints = getMaxPointsForGrade(targetGrade);
        
        // Extract current factor data from payload
        Map<String, Object> currentFactors = extractCurrentFactorData(payload);
        System.out.println("Current factors extracted: " + currentFactors.keySet());
        
        // Build result with GSSG structure and LOCKED titles
        Map<String, Object> validatedFactors = new LinkedHashMap<>();
        int totalPoints = 0;
        
        String[] gssgOrder = {"1", "2", "3", "4A", "4B", "5", "6"};
        
        for (String factorId : gssgOrder) {
            String key = "Factor " + factorId;
            
            // Get current data or defaults
            Map<String, Object> currentData = extractFactorFromCurrent(currentFactors, key);
            
            String level = null;
            String rationale = null;
            
            if (currentData != null) {
                level = (String) currentData.get("level");
                rationale = (String) currentData.get("rationale");
                System.out.println("  " + key + ": level=" + level);
            }
            
            // If level is missing or this is the updated factor, regenerate intelligently
            if (level == null || level.isEmpty() || key.equals(updatedFactorKey)) {
                String numStr = factorId.replaceAll("[^0-9]", "");
                int fid = 1;
                try {
                    if (!numStr.isEmpty()) fid = Integer.parseInt(numStr);
                } catch (Exception ignored) {}
                level = getIntelligentGssgLevel(fid, duties, targetGrade);
                System.out.println("  Regenerated " + key + " level: " + level);
            }
            
            level = normalizeGssgLevel(factorId, level);
            int points = getGssgPointsForLevel(factorId, level);
            
            // Generate substantive rationale if missing
            if (rationale == null || rationale.isEmpty() || rationale.contains("Default supervisory")) {
                rationale = buildGssgRationale(
                    Integer.parseInt(factorId.replaceAll("[^0-9]", "")), 
                    level, duties, 
                    factorId.contains("A") || factorId.contains("B") ? factorId : null
                );
            }
            
            Map<String, Object> factorData = new LinkedHashMap<>();
            factorData.put("header", buildLockedFactorHeader(factorId, level, points, true));
            factorData.put("level", level);
            factorData.put("points", points);
            factorData.put("rationale", rationale);
            
            validatedFactors.put(key, factorData);
            totalPoints += points;
            
            System.out.println("  Final " + key + ": " + buildLockedFactorHeader(factorId, level, points, true));
        }
        
        // Adjust to grade range (titles remain locked)
        totalPoints = adjustGSSGToGradeRangeLocked(validatedFactors, totalPoints, minPoints, maxPoints);
        
        // CRITICAL FIX: Always calculate grade range for supervisory positions
        String gradeRange = getGssgGradeRange(totalPoints);
        
        // Ensure finalGrade is set
        if (targetGrade == null || targetGrade.isEmpty()) {
            targetGrade = calculateFinalGrade(totalPoints);
        }
        
        System.out.println("GSSG update complete: " + totalPoints + " pts = " + targetGrade + " (" + gradeRange + ")");
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("factors", validatedFactors);
        result.put("totalPoints", totalPoints);
        result.put("finalGrade", targetGrade);
        result.put("gradeRange", gradeRange);
        result.put("isSupervisory", true);
        return result;
        
    } catch (Exception e) {
        System.err.println("EXCEPTION in updateGSSGFactorPointsLocked:");
        System.err.println("  Message: " + e.getMessage());
        System.err.println("  Class: " + e.getClass().getName());
        System.err.println("  Cause: " + (e.getCause() != null ? e.getCause().getMessage() : "none"));
        e.printStackTrace();
        throw new Exception("GSSG update failed: " + 
            (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
    }
}

private Map<String, Object> updateFESFactorPointsLocked(Map<String, String> existingFactors,
                                                         Map<String, Object> payload,
                                                         String duties, String targetGrade,
                                                         String updatedFactorKey) throws Exception {
    System.out.println("=== UPDATE FES FACTORS (LOCKED TITLES) ===");
    System.out.println("Target grade: " + targetGrade);
    System.out.println("Existing factors count: " + existingFactors.size());
    System.out.println("Duties length: " + (duties != null ? duties.length() : 0));
    
    try {
        // Extract current factor data from payload
        Map<String, Object> currentFactors = extractCurrentFactorData(payload);
        System.out.println("Current factors extracted: " + currentFactors.keySet());
        
        // Build result with FES structure and LOCKED titles
        Map<String, Object> validatedFactors = new LinkedHashMap<>();
        int totalPoints = 0;
        
        for (int i = 1; i <= 9; i++) {
            String factorId = String.valueOf(i);
            String key = "Factor " + i;
            
            // Get current data or defaults
            Map<String, Object> currentData = extractFactorFromCurrent(currentFactors, key);
            
            String level = null;
            String rationale = null;
            
            if (currentData != null) {
                level = (String) currentData.get("level");
                rationale = (String) currentData.get("rationale");
                System.out.println("  " + key + ": level=" + level);
            }
            
            // If level is missing or this is the updated factor, regenerate intelligently
            if (level == null || level.isEmpty() || key.equals(updatedFactorKey)) {
                level = getIntelligentLevel(i, duties);
                System.out.println("  Regenerated " + key + " level: " + level);
            }
            
            level = normalizeAndValidateFesLevel(factorId, level);
            int points = getPointsForLevel(factorId, level);
            
            // Generate substantive rationale if missing
            if (rationale == null || rationale.isEmpty()) {
                rationale = generateSubstantiveRationale(i, level, duties);
            }
            
            Map<String, Object> factorData = new LinkedHashMap<>();
            // USE LOCKED TITLE - critical fix
            factorData.put("header", buildLockedFactorHeader(factorId, level, points, false));
            factorData.put("level", level);
            factorData.put("points", points);
            factorData.put("rationale", rationale);
            
            validatedFactors.put(key, factorData);
            totalPoints += points;
            
            System.out.println("  Final " + key + ": " + buildLockedFactorHeader(factorId, level, points, false));
        }
        
        // Avoid forbidden grades
        totalPoints = avoidForbiddenGradeRanges(totalPoints, validatedFactors);
        
        String finalGrade = calculateFinalGrade(totalPoints);
        String gradeRange = calculateGradeRange(totalPoints);
        
        System.out.println("FES update complete: " + totalPoints + " pts = " + finalGrade);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("factors", validatedFactors);
        result.put("totalPoints", totalPoints);
        result.put("finalGrade", finalGrade);
        result.put("gradeRange", gradeRange);
        result.put("isSupervisory", false);
        return result;
        
    } catch (Exception e) {
        System.err.println("EXCEPTION in updateFESFactorPointsLocked:");
        System.err.println("  Message: " + e.getMessage());
        System.err.println("  Class: " + e.getClass().getName());
        System.err.println("  Cause: " + (e.getCause() != null ? e.getCause().getMessage() : "none"));
        e.printStackTrace();
        throw new Exception("FES update failed: " + 
            (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
    }
}

private String getGssgGradeRange(int totalPoints) {
    if (totalPoints >= 855 && totalPoints <= 1100) return "855-1100"; // GS-05
    if (totalPoints >= 1355 && totalPoints <= 1600) return "1355-1600"; // GS-07
    if (totalPoints >= 1855 && totalPoints <= 2100) return "1855-2100"; // GS-09
    if (totalPoints >= 2355 && totalPoints <= 2750) return "2355-2750"; // GS-11
    if (totalPoints >= 2755 && totalPoints <= 3150) return "2755-3150"; // GS-12
    if (totalPoints >= 3155 && totalPoints <= 3600) return "3155-3600"; // GS-13
    if (totalPoints >= 3605 && totalPoints <= 4050) return "3605-4050"; // GS-14
    if (totalPoints >= 4055) return "4055+"; // GS-15
    
    // Handle forbidden ranges by adjusting upward
    if (totalPoints >= 1105 && totalPoints <= 1350) return "1355-1600"; // Force GS-07
    if (totalPoints >= 1605 && totalPoints <= 1850) return "1855-2100"; // Force GS-09
    if (totalPoints >= 2105 && totalPoints <= 2350) return "2355-2750"; // Force GS-11
    
    return "Unknown";
}

private Map<String, Object> updateSummarySectionsResponse(int totalPoints, String finalGrade, boolean isSupervisory) {
    String gradeRange = isSupervisory ? 
        getGssgGradeRange(totalPoints) : 
        getPointRangeForGrade(finalGrade);

    if (finalGrade == null || finalGrade.isEmpty()) {
        finalGrade = calculateFinalGrade(totalPoints);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("totalPoints", totalPoints);
    result.put("finalGrade", finalGrade);
    result.put("gradeRange", gradeRange);

    System.out.println("Summary sections response: totalPoints=" + totalPoints + 
                      ", finalGrade=" + finalGrade + 
                      ", gradeRange=" + gradeRange);

    return result;
}

// Helper to enforce summary fields on any result map
private Map<String, Object> ensureSummaryFields(Map<String, Object> result, boolean isSupervisory) {
    if (result == null) result = new LinkedHashMap<>();
    
    // CRITICAL: If result is immutable (from Map.of()), create a mutable copy
    if (!(result instanceof LinkedHashMap)) {
        result = new LinkedHashMap<>(result);
    }
    
    int totalPoints = safeInt(result.get("totalPoints"));
    String finalGrade = safeStr(result.get("finalGrade"));

    Map<String, Object> summary = updateSummarySectionsResponse(totalPoints, finalGrade, isSupervisory);
    result.putAll(summary);
    return result;
}

private int safeInt(Object v) {
    if (v instanceof Number) return ((Number) v).intValue();
    try {
        return Integer.parseInt(String.valueOf(v));
    } catch (Exception e) {
        return 0;
    }
}

private String safeStr(Object v) {
    return v == null ? "" : String.valueOf(v);
}

/**
 * CRITICAL: Adjust points to AVOID forbidden grade ranges
 * GS-6: 1101-1354, GS-8: 1601-1854, GS-10: 2101-2354
 */
private int avoidForbiddenGradeRanges(int totalPoints, Map<String, Object> factors) {
    int original = totalPoints;
    
    // Forbidden ranges
    if (totalPoints >= 1101 && totalPoints <= 1354) {
        // In GS-6 forbidden range - force to GS-7
        totalPoints = 1355;
        System.out.println("Avoiding forbidden GS-6 range [1101-1354]. Adjusted " + original + " → " + totalPoints);
    }
    else if (totalPoints >= 1601 && totalPoints <= 1854) {
        // In GS-8 forbidden range - force to GS-9
        totalPoints = 1855;
        System.out.println("Avoiding forbidden GS-8 range [1601-1854]. Adjusted " + original + " → " + totalPoints);
    }
    else if (totalPoints >= 2101 && totalPoints <= 2354) {
        // In GS-10 forbidden range - force to GS-11
        totalPoints = 2355;
        System.out.println("Avoiding forbidden GS-10 range [2101-2354]. Adjusted " + original + " → " + totalPoints);
    }
    
    return totalPoints;
}

/**
 * FIX: Update major duties - preserve original system
 */
@PostMapping("/update-major-duties")
public Map<String, String> updateMajorDuties(@RequestBody Map<String, String> body) throws Exception {
    String currentContent = body.getOrDefault("currentContent", "");
    String factorHeader = body.getOrDefault("factorHeader", ""); // Full header with name
    String supervisoryLevel = body.getOrDefault("supervisoryLevel", "Non-Supervisory");
    
    if (currentContent.isBlank()) {
        return Map.of("rewritten", "No content provided.", "error", "true");
    }

    // DETERMINE SYSTEM from factor header
    boolean isSupervisory = isSupervisoryFactorHeader(factorHeader);
    
    System.out.println("=== UPDATE MAJOR DUTIES ===");
    System.out.println("Factor header: " + factorHeader);
    System.out.println("System: " + (isSupervisory ? "GSSG SUPERVISORY" : "FES NON-SUPERVISORY"));

    try {
        String prompt;
        
        if (isSupervisory) {
            prompt = String.format("""
                You are a federal HR specialist expert in GSSG supervisory classification.
                
                Rewrite this GSSG supervisory factor content to be more professional and clear:
                Factor: %s
                Supervisory Level: %s
                
                Keep the supervisory meaning and responsibility level.
                Return ONLY the improved content - no explanation, no factor name, no metadata.
                
                %s
                """, factorHeader, supervisoryLevel, currentContent);
        } else {
            prompt = String.format("""
                You are a federal HR specialist expert in FES non-supervisory classification.
                
                Rewrite this FES factor content to be more professional and clear:
                Factor: %s
                
                Keep the technical/analytical meaning and complexity level.
                Return ONLY the improved content - no explanation, no factor name, no metadata.
                
                %s
                """, factorHeader, currentContent);
        }
        
        String rewritten = callOpenAIWithTimeout(prompt, 30);
        System.out.println("Rewrite successful");
        return Map.of("rewritten", rewritten.trim(), "system", isSupervisory ? "GSSG" : "FES");
        
    } catch (Exception e) {
        System.err.println("Error rewriting: " + e.getMessage());
        return Map.of("rewritten", "Error: " + e.getMessage(), "error", "true");
    }
}

/**
 * Determine if factor header belongs to GSSG or FES
 */
private boolean isSupervisoryFactorHeader(String header) {
    if (header == null) return false;
    
    String lower = header.toLowerCase();
    
    // GSSG supervisory factor names
    if (lower.contains("program scope and effect") ||
        lower.contains("organizational setting") ||
        lower.contains("supervisory & managerial authority") ||
        lower.contains("difficulty of typical work directed") ||
        lower.contains("personal contacts (nature") ||
        lower.contains("personal contacts (purpose")) {
        return true;
    }
    
    // FES non-supervisory factor names
    if (lower.contains("knowledge required by the position") ||
        lower.contains("supervisory controls") ||
        lower.contains("guidelines") ||
        lower.contains("complexity") ||
        (lower.contains("scope and effect") && !lower.contains("program scope")) ||
        lower.contains("personal contacts") ||
        lower.contains("purpose of contacts") ||
        lower.contains("physical demands") ||
        lower.contains("work environment")) {
        return false;
    }
    
    // Default: FES
    return false;
}

/**
 * Get the maximum valid level for each factor according to OPM standards
 */
private int getMaxLevelForFactor(String factorNum) {
    switch (factorNum) {
        case "1": return 9; // Factor 1 (Knowledge) has levels 1-1 through 1-9
        case "2": return 5; // Factor 2 (Supervisory Controls) has levels 2-1 through 2-5
        case "3": return 5; // Factor 3 (Guidelines) has levels 3-1 through 3-5
        case "4": return 6; // Factor 4 (Complexity) has levels 4-1 through 4-6
        case "5": return 6; // Factor 5 (Scope and Effect) has levels 5-1 through 5-6
        case "6": return 4; // Factor 6 (Personal Contacts) has levels 6-1 through 6-4
        case "7": return 4; // Factor 7 (Purpose of Contacts) has levels 7-1 through 7-4
        case "8": return 3; // Factor 8 (Physical Demands) has levels 8-1 through 8-3
        case "9": return 3; // Factor 9 (Work Environment) has levels 9-1 through 9-3
        default:
            System.err.println("Unknown factor number: " + factorNum);
            return 1;
    }
}

// Non-overlapping, exhaustive mapping to allowed two-grade intervals only
private String calculateGradeRange(int totalPoints) {
    if (totalPoints >= 855 && totalPoints <= 1100) return "855-1100"; // GS-05
    if (totalPoints >= 1355 && totalPoints <= 1600) return "1355-1600"; // GS-07
    if (totalPoints >= 1855 && totalPoints <= 2100) return "1855-2100"; // GS-09
    if (totalPoints >= 2355 && totalPoints <= 2750) return "2355-2750"; // GS-11
    if (totalPoints >= 2755 && totalPoints <= 3150) return "2755-3150"; // GS-12
    if (totalPoints >= 3155 && totalPoints <= 3600) return "3155-3600"; // GS-13
    if (totalPoints >= 3605 && totalPoints <= 4050) return "3605-4050"; // GS-14
    if (totalPoints >= 4055) return "4055+"; // GS-15
    
    // Handle forbidden ranges by adjusting upward
    if (totalPoints >= 1105 && totalPoints <= 1350) return "1355-1600"; // Force GS-07
    if (totalPoints >= 1605 && totalPoints <= 1850) return "1855-2100"; // Force GS-09
    if (totalPoints >= 2105 && totalPoints <= 2350) return "2355-2750"; // Force GS-11
    
    return "Unknown";
}

private String calculateFinalGrade(int totalPoints) {
    // Official OPM two-grade interval assignments only
    if (totalPoints >= 855 && totalPoints <= 1100) return "GS-05";
    if (totalPoints >= 1355 && totalPoints <= 1600) return "GS-07";
    if (totalPoints >= 1855 && totalPoints <= 2100) return "GS-09";
    if (totalPoints >= 2355 && totalPoints <= 2750) return "GS-11";
    if (totalPoints >= 2755 && totalPoints <= 3150) return "GS-12";
    if (totalPoints >= 3155 && totalPoints <= 3600) return "GS-13";
    if (totalPoints >= 3605 && totalPoints <= 4050) return "GS-14";
    if (totalPoints >= 4055) return "GS-15";
    
    // Handle forbidden ranges by forcing upward to next valid grade
    if (totalPoints >= 1105 && totalPoints <= 1350) return "GS-07"; // Skip GS-06
    if (totalPoints >= 1605 && totalPoints <= 1850) return "GS-09"; // Skip GS-08
    if (totalPoints >= 2105 && totalPoints <= 2350) return "GS-11"; // Skip GS-10
    
    return "GS-05"; // Default fallback
}

private int getMinPointsForGrade(String grade) {
    switch (grade.toUpperCase()) {
        case "GS-05": case "GS-5": return 855;
        case "GS-07": case "GS-7": return 1355;
        case "GS-09": case "GS-9": return 1855;
        case "GS-11": return 2355;
        case "GS-12": return 2755;
        case "GS-13": return 3155;
        case "GS-14": return 3605;
        case "GS-15": return 4055;
        default: return 855;
    }
}

private int getMaxPointsForGrade(String grade) {
    switch (grade.toUpperCase()) {
        case "GS-05": case "GS-5": return 1100;
        case "GS-07": case "GS-7": return 1600;
        case "GS-09": case "GS-9": return 2100;
        case "GS-11": return 2750;
        case "GS-12": return 3150;
        case "GS-13": return 3600;
        case "GS-14": return 4050;
        case "GS-15": return Integer.MAX_VALUE; // No upper limit
        default: return Integer.MAX_VALUE;
    }
}

// --- Helper: Only allow numeric grade ranges ---
private String getPointRangeForGrade(String grade) {
    if (grade == null) return "Unknown";
    switch (grade.toUpperCase()) {
        case "GS-05": case "GS-5": return "855-1100";
        case "GS-07": case "GS-7": return "1355-1600";
        case "GS-09": case "GS-9": return "1855-2100";
        case "GS-11": return "2355-2750";
        case "GS-12": return "2755-3150";
        case "GS-13": return "3155-3600";
        case "GS-14": return "3605-4050";
        case "GS-15": return "4055+";
        default: return "Unknown";
    }
}

// Helper methods for level navigation
private String getNextHigherLevel(String currentLevel, Map<String, Integer> factorLevels) {
    String[] parts = currentLevel.split("-");
    if (parts.length != 2) return null;
    
    try {
        String prefix = parts[0];
        int currentNum = Integer.parseInt(parts[1]);
        String nextLevel = prefix + "-" + (currentNum + 1);
        
        return factorLevels.containsKey(nextLevel) ? nextLevel : null;
    } catch (NumberFormatException e) {
        return null;
    }
}

/**
 * Helper method to extract JSON from AI response that might contain extra text
 */
private String extractJsonFromResponse(String response) {
    if (response == null) return null;
    int firstBrace = response.indexOf("{");
    int lastBrace = response.lastIndexOf("}");
    if (firstBrace == -1 || lastBrace == -1 || firstBrace >= lastBrace) return null;
    String json = response.substring(firstBrace, lastBrace + 1);

    // Remove trailing commas before closing braces
    json = json.replaceAll(",\\s*}", "}");
    json = json.replaceAll(",\\s*]", "]");

    // Remove any lines that start with // or #
    json = json.replaceAll("(?m)^\\s*(//|#).*$", "");

    // Remove any comments inside the JSON
    json = json.replaceAll("/\\*.*?\\*/", "");

    // Remove invalid field names (unquoted keys)
    json = json.replaceAll("(?m)^\\s*([A-Za-z0-9_]+)\\s*:", "\"$1\":");

    return json.trim();
}

/**
 * Fix spacing and formatting issues in generated PD text.
 */
public String fixPDFormatting(String pdText) {
    if (pdText == null || pdText.trim().isEmpty()) return "";

    String text = pdText.trim();

    // Remove lines that contain only asterisks (with or without whitespace)
    text = text.replaceAll("(?m)^\\s*\\*+\\s*$\\n?", "");

    // Remove asterisks that are alone on a line or surrounded by blank lines
    text = text.replaceAll("(?m)^\\s*\\*+\\s*$", "");

    // Normalize line endings and whitespace
    text = text.replaceAll("\\r\\n?", "\n");
    text = text.replaceAll("\\n{3,}", "\n\n");

    // Add space between letters and numbers (e.g., GS13 -> GS 13, Factor3 -> Factor 3)
    text = text.replaceAll("([a-zA-Z])([0-9])", "$1 $2");
    text = text.replaceAll("([0-9])([a-zA-Z])", "$1 $2");

    // Ensure section headers are always on their own line
    text = text.replaceAll("(Duties/Responsibilities Context:[^\n]+)[ \\t]*\\*\\*INTRODUCTION\\*\\*", "$1\n\n\n**INTRODUCTION**");

    // --- HEADER variable splitting ---
    text = text.replaceAll("Job Series:\\s*([^\\n]+?)Position Title:", "Job Series: $1\nPosition Title:");
    text = text.replaceAll("Position Title:\\s*([^\\n]+?)Agency:", "Position Title: $1\nAgency:");
    text = text.replaceAll("Agency:\\s*([^\\n]+?)Organization:", "Agency: $1\nOrganization:");
    text = text.replaceAll("Organization:\\s*([^\\n]+?)Supervisory Level:", "Organization: $1\nSupervisory Level:");
    text = text.replaceAll("Supervisory Level:\\s*([^\\n]+?)Duties/Responsibilities Context:", "Supervisory Level: $1\nDuties/Responsibilities Context:");

    // --- Section headers bold and spaced ---
    String[] sectionHeaders = {
        "HEADER",
        "INTRODUCTION",
        "MAJOR DUTIES",
        "FACTOR EVALUATION - COMPLETE ANALYSIS",
        "EVALUATION SUMMARY",
        "CONDITIONS OF EMPLOYMENT",
        "TITLE AND SERIES DETERMINATION",
        "FAIR LABOR STANDARDS ACT DETERMINATION"
    };
    for (String header : sectionHeaders) {
        String regex = "\\*{0,2}\\s*" + header + "\\s*:?\\s*\\*{0,2}";
        text = text.replaceAll("(?i)" + regex, "\n\n**" + header + "**\n\n");
    }

    // --- Factor header spacing fix ---
    text = text.replaceAll("Factor\\s*([0-9])([–-])\\s*", "Factor $1 $2 ");
    text = text.replaceAll("Level\\s*([0-9]+)-([0-9]+),\\s*([0-9]+)\\s*Points", "Level $1-$2, $3 Points");
    text = text.replaceAll("(Level\\s*[0-9]+-[0-9]+),([0-9]+\\s*Points)", "$1, $2");

    // --- Ensure factor headers are bold and spaced ---
    text = text.replaceAll(
        "\\*\\*\\s*Factor\\s*([0-9])\\s*[–-]\\s*([^\\n]+?)\\s*Level\\s*([0-9]+)-([0-9]+),\\s*([0-9]+)\\s*Points\\s*\\*\\*",
        "\n\n**Factor $1 – $2 Level $3-$4, $5 Points**\n\n"
    );
    text = text.replaceAll(
        "Factor\\s*([0-9])\\s*[–-]\\s*([^\\n]+?)\\s*Level\\s*([0-9]+)-([0-9]+),\\s*([0-9]+)\\s*Points",
        "\n\n**Factor $1 – $2 Level $3-$4, $5 Points**\n\n"
    );

    // --- Evaluation summary spaced and bold ---
    text = text.replaceAll("Total Points:\\s*(\\d+)", "\n\n**Total Points: $1**\n");
    text = text.replaceAll("Final Grade:\\s*(GS-\\d+)", "\n\n**Final Grade: $1**\n");
    text = text.replaceAll("Grade Range:\\s*([0-9\\-+]+)", "\n\n**Grade Range: $1**\n");

    // --- Remove extra spaces and blank lines ---
    text = text.replaceAll("[ \\t]+\\n", "\n");
    text = text.replaceAll("\\n[ \\t]+", "\n");
    text = text.replaceAll("\\n{3,}", "\n\n");
    text = text.replaceAll("^\\n+", "");
    text = text.replaceAll("\\n+$", "");

    // --- Fix run-together header lines (e.g., "**HEADER** Job Series: ...") ---
    text = text.replaceAll("(\\*\\*HEADER\\*\\*)\\s*([^\\n]+)", "$1\n$2");
    text = text.replaceAll("(\\*\\*INTRODUCTION\\*\\*)\\s*([^\\n]+)", "$1\n$2");
    text = text.replaceAll("(\\*\\*MAJOR DUTIES\\*\\*)\\s*([^\\n]+)", "$1\n$2");
    text = text.replaceAll("(\\*\\*FACTOR EVALUATION - COMPLETE ANALYSIS\\*\\*)\\s*([^\\n]+)", "$1\n$2");
    text = text.replaceAll("(\\*\\*EVALUATION SUMMARY\\*\\*)\\s*([^\\n]+)", "$1\n$2");
    text = text.replaceAll("(\\*\\*CONDITIONS OF EMPLOYMENT\\*\\*)\\s*([^\\n]+)", "$1\n$2");
    text = text.replaceAll("(\\*\\*TITLE AND SERIES DETERMINATION\\*\\*)\\s*([^\\n]+)", "$1\n$2");
    text = text.replaceAll("(\\*\\*FAIR LABOR STANDARDS ACT DETERMINATION\\*\\*)\\s*([^\\n]+)", "$1\n$2");

    // --- Remove any double spaces ---
    text = text.replaceAll(" +", " ");

    return text.trim();
}

@PostMapping("/classify-series")
public List<Map<String, String>> classifySeries(@RequestBody Map<String, String> body) throws Exception {
    String duties = body.getOrDefault("duties", "");
    if (duties.isBlank()) {
        return List.of(Map.of("error", "No duties provided"));
    }

    String pdfContext = getAutoPdfContext();

    String prompt = pdfContext + String.format("""
        Duties may be listed with percentages. Duties with higher percentages must be given greater weight and considered more important in your analysis. Percentages if present depict the importance of that duty.
        You are an expert in federal HR classification with access to official OPM standards provided above.
        
        Based on the following job duties and the OPM classification standards, recommend the top three
        most appropriate federal job series codes (e.g., 1801, 1811, 0132, 0343, etc.).
        
        Use the reference materials provided to ensure accurate series classification based on:
        - Primary work performed
        - Knowledge and skills required
        - Occupational group definitions
        - Series-specific criteria from OPM standards
        
        Respond ONLY with a numbered list in this exact format:
        1. 1801 - General Inspection, Investigation, Enforcement, and Compliance
        2. 1811 - Criminal Investigator
        3. 0132 - Intelligence Operations Specialist

        DO NOT include any explanation or reasoning - ONLY the numbered list.

        Duties:
        %s
        """, duties);

    String response = callOpenAIWithTimeout(prompt, 20);

    // Parse the response into a list of series code/title maps
    List<Map<String, String>> results = new ArrayList<>();
    Pattern p = Pattern.compile("\\d+\\.\\s*(\\d{4})\\s*[-:]?\\s*(.*)");
    Matcher m = p.matcher(response.trim());
    while (m.find()) {
        String code = m.group(1);
        String title = m.group(2).trim();
        results.add(Map.of("seriesCode", code, "seriesTitle", title));
    }
    
    // Fallback: if nothing matched, try to extract just the series codes
    if (results.isEmpty()) {
        Pattern codePattern = Pattern.compile("(\\d{4})");
        Matcher codeMatcher = codePattern.matcher(response);
        while (codeMatcher.find() && results.size() < 3) {
            results.add(Map.of("seriesCode", codeMatcher.group(1), "seriesTitle", ""));
        }
    }
    
    // Final fallback: return the whole response
    if (results.isEmpty()) {
        results.add(Map.of("seriesCode", response.trim(), "seriesTitle", ""));
    }
    
    return results;
}

public String extractPdfText(String pdfPath) throws Exception {
    try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(new File(pdfPath))) {
        PDFTextStripper stripper = new PDFTextStripper();
        return stripper.getText(document);
    }
}

private static String cachedHandbookText = null;

public String getHandbookText() {
    if (cachedHandbookText == null) {
        try {
            // Adjust the path as needed for your deployment
            cachedHandbookText = extractPdfText("src/main/resources/static/occupationalhandbook.pdf");
        } catch (Exception e) {
            cachedHandbookText = "";
            System.err.println("Failed to extract PDF text: " + e.getMessage());
        }
    }
    return cachedHandbookText;
}

/**
 * Add this method to your PdGeneratorController to coordinate evaluation and PD generation
 */
@PostMapping("/generate-with-evaluation")
public void generatePdWithEvaluation(@RequestBody PdRequest request, HttpServletResponse response) throws Exception {
    System.out.println("=== COORDINATED PD GENERATION STARTED ===");

    response.setContentType("text/event-stream");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-cache");
    response.setHeader("Connection", "keep-alive");
    response.setHeader("Access-Control-Allow-Origin", "*");

    PrintWriter writer = response.getWriter();

    try {
        if (request == null || request.getHistoricalData() == null || request.getHistoricalData().trim().isEmpty()) {
            writer.println("data: {\"error\":\"Job duties are required\"}\n");
            writer.flush();
            return;
        }

        String supervisoryLevel = request.getSupervisoryLevel() != null ? request.getSupervisoryLevel() : "Non-Supervisory";
        request.setSupervisoryLevel(supervisoryLevel);

        writer.println("data: {\"status\":\"Analyzing grade requirements...\"}\n");
        writer.flush();

        // 1. Grade analysis
        List<Map<String, Object>> gradeRelevancy = getAIGSGradeRelevancy(request.getHistoricalData(), supervisoryLevel);
        String targetGrade = null;
        if (gradeRelevancy != null && !gradeRelevancy.isEmpty()) {
            Object g = gradeRelevancy.get(0).get("grade");
            if (g != null) targetGrade = g.toString();
        }
        List<String> allowedGrades = List.of("GS-5", "GS-7", "GS-9", "GS-11", "GS-12", "GS-13", "GS-14", "GS-15");
        if (targetGrade == null || !allowedGrades.contains(targetGrade)) {
            targetGrade = "GS-13";
        }

        writer.println("data: {\"status\":\"Generating evaluation statement...\"}\n");
        writer.flush();

        // 2. Generate evaluation statement
        Map<String, String> evaluationRequest = new HashMap<>();
        evaluationRequest.put("duties", request.getHistoricalData());
        evaluationRequest.put("gsGrade", targetGrade);
        evaluationRequest.put("jobSeries", request.getJobSeries() != null ? request.getJobSeries() : "");
        evaluationRequest.put("jobTitle", request.getSubJobSeries() != null ? request.getSubJobSeries() : "");
        evaluationRequest.put("positionTitle", request.getSubJobSeries() != null ? request.getSubJobSeries() : "");
        evaluationRequest.put("supervisoryLevel", supervisoryLevel);

        generateEvaluationStatement(evaluationRequest, response);
        String evaluationStatement = "";

        // 3. Extract exact factor values from evaluation statement
        Map<String, String> factorLevels = extractFactorLevels(evaluationStatement);
        Map<String, Integer> factorPoints = extractFactorPoints(evaluationStatement);
        Integer totalPoints = extractPointsFromEvaluation(evaluationStatement);
        String finalGrade = extractGradeFromEvaluation(evaluationStatement);
        String gradeRange = extractRangeFromEvaluation(evaluationStatement);

        // 4. Validate extraction worked
        if (factorLevels.size() != 9 || factorPoints.size() != 9) {
            System.err.println("WARNING: Failed to extract all 9 factors. Using defaults.");
            factorLevels = getDefaultFactorLevels(targetGrade);
            final Map<String, Integer> defaultFactorPoints = getDefaultFactorPoints(targetGrade);
            totalPoints = defaultFactorPoints.values().stream().mapToInt(Integer::intValue).sum();
            finalGrade = targetGrade;
            gradeRange = getPointRangeForGrade(targetGrade);
        }

        // 5. Lock these exact values into the request
        request.setFactorLevels(factorLevels);
        request.setFactorPoints(factorPoints);
        request.setTotalPoints(totalPoints);
        request.setGsGrade(finalGrade);
        request.setGradeRange(gradeRange);

        System.out.println("Locked factor values:");
        factorLevels.forEach((k, v) -> System.out.println("  Factor " + k + ": Level " + v + ", " + factorPoints.get(k) + " pts"));
        System.out.println("Total: " + totalPoints + " pts = " + finalGrade + " (" + gradeRange + ")");

        writer.println("data: {\"status\":\"Generating position description with locked values...\"}\n");
        writer.flush();

        // 6. Generate PD with locked values
        String prompt = pdService.buildPrompt(request);

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", 
            "You are an expert federal HR classification specialist. " +
            "Create complete, professional position descriptions using the EXACT factor values provided. " +
            "DO NOT recalculate or change any factor levels or points. " +
            "Copy the provided values exactly into the Factor Evaluation section."
        ));
        messages.add(new Message("user", prompt));

        OpenAIRequest openaiRequest = new OpenAIRequest("gpt-4", messages, true);
        openaiRequest.setMax_tokens(8000);

        ObjectMapper objectMapper = new ObjectMapper();
        String requestBody = objectMapper.writeValueAsString(openaiRequest);

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Authorization", "Bearer " + pdService.getOpenaiApiKey())
            .header("Content-Type", "application/json")
            .timeout(java.time.Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();

        HttpResponse<InputStream> openaiResponse = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

        if (openaiResponse.statusCode() != 200) {
            writer.println("data: {\"error\":\"OpenAI API Error\"}\n");
            writer.flush();
            return;
        }

        StringBuilder fullPD = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(openaiResponse.body()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6);
                    if (!"[DONE]".equals(data.trim()) && !data.trim().isEmpty()) {
                        try {
                            JsonNode jsonNode = objectMapper.readTree(data);
                            if (jsonNode.has("choices") && jsonNode.get("choices").size() > 0) {
                                JsonNode choice = jsonNode.get("choices").get(0);
                                if (choice.has("delta") && choice.get("delta").has("content")) {
                                    String content = choice.get("delta").get("content").asText();
                                    if (!content.trim().isEmpty()) {
                                        writer.println("data: {\"response\":\"" + escapeJson(content) + "\"}\n");
                                        writer.flush();
                                        fullPD.append(content);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("Error parsing streaming response: " + e.getMessage());
                        }
                    }
                }
            }
        }

        // 7. Validate and correct the generated PD
        String validatedPD = enforceFactorConsistency(
            fullPD.toString(),
            factorLevels,
            factorPoints,
            totalPoints,
            finalGrade,
            gradeRange
        );

        Map<String, Object> finalResult = new HashMap<>();
        finalResult.put("evaluationStatement", evaluationStatement);
        finalResult.put("positionDescription", validatedPD);
        finalResult.put("finalGrade", finalGrade);
        finalResult.put("gradeRange", gradeRange);
        finalResult.put("totalPoints", totalPoints);
        finalResult.put("supervisoryLevel", supervisoryLevel);
        finalResult.put("factorsValidated", true);

        writer.println("data: {\"complete\":true,\"results\":" + objectMapper.writeValueAsString(finalResult) + "}\n");
        writer.println("data: [DONE]\n");
        writer.flush();

        System.out.println("=== COORDINATED GENERATION COMPLETE - VALUES LOCKED ===");

    } catch (Exception e) {
        System.err.println("EXCEPTION in generatePdWithEvaluation: " + e.getMessage());
        e.printStackTrace();
        writer.println("data: {\"error\":\"Generation failed: " + escapeJson(e.getMessage()) + "\"}\n");
    } finally {
        try {
            writer.flush();
            writer.close();
        } catch (Exception e) {
            System.err.println("Error closing writer: " + e.getMessage());
        }
    }
}

/**
 * Extract factor levels from evaluation statement
 */
private Map<String, String> extractFactorLevels(String evaluation) {
    Map<String, String> levels = new HashMap<>();
    Pattern pattern = Pattern.compile("Factor\\s+(\\d+)[^\\n]*?Level\\s+(\\d+-\\d+)");
    Matcher matcher = pattern.matcher(evaluation);
    
    while (matcher.find()) {
        String factorNum = matcher.group(1);
        String level = matcher.group(2);
        levels.put(factorNum, level);
    }
    
    System.out.println("Extracted factor levels: " + levels);
    return levels;
}

/**
 * Extract factor points from evaluation statement
 */
private Map<String, Integer> extractFactorPoints(String evaluation) {
    Map<String, Integer> points = new HashMap<>();
    Pattern pattern = Pattern.compile("Factor\\s+(\\d+)[^\\n]*?Level\\s+\\d+-\\d+,\\s*(\\d+)\\s*Points");
    Matcher matcher = pattern.matcher(evaluation);
    
    while (matcher.find()) {
        String factorNum = matcher.group(1);
        int factorPoints = Integer.parseInt(matcher.group(2));
        points.put(factorNum, factorPoints);
    }
    
    System.out.println("Extracted factor points: " + points);
    return points;
}

/**
 * Enforce factor consistency in generated PD
 */
private String enforceFactorConsistency(String generatedPD,
                            Map<String, String> correctLevels,
                            Map<String, Integer> correctPoints,
                            int correctTotal,
                            String correctGrade,
                            String correctRange) {
    
    System.out.println("Enforcing factor consistency...");
    String validated = generatedPD;
    
    // Replace each factor with correct values
    for (int i = 1; i <= 9; i++) {
        String factorNum = String.valueOf(i);
        String correctLevel = correctLevels.get(factorNum);
        Integer correctPts = correctPoints.get(factorNum);
        
        if (correctLevel != null && correctPts != null) {
            // Find and replace factor headers with wrong values
            Pattern wrongPattern = Pattern.compile(
                "(Factor\\s+" + i + "\\s+[^\\n]*?)Level\\s+\\d+-\\d+,\\s*\\d+\\s*Points"
            );
            Matcher matcher = wrongPattern.matcher(validated);
            
            if (matcher.find()) {
                String replacement = matcher.group(1) + "Level " + correctLevel + ", " + correctPts + " Points";
                validated = matcher.replaceFirst(replacement);
                System.out.println("Corrected Factor " + i + " to Level " + correctLevel + ", " + correctPts + " pts");
            }
        }
    }
    
    // Replace total points, grade, and range
    validated = validated.replaceAll("\\*\\*Total Points:\\s*\\*\\*\\s*\\d+", 
                                    "**Total Points:** " + correctTotal);
    validated = validated.replaceAll("\\*\\*Final Grade:\\s*\\*\\*\\s*GS-\\d+", 
                                    "**Final Grade:** " + correctGrade);
    validated = validated.replaceAll("\\*\\*Grade Range:\\s*\\*\\*\\s*[\\d-]+", 
                                    "**Grade Range:** " + correctRange);
    
    System.out.println("Factor consistency enforced: Total=" + correctTotal + ", Grade=" + correctGrade);
    return validated;
}

/**
 * Get default factor levels for a grade (fallback)
 */
private Map<String, String> getDefaultFactorLevels(String grade) {
    Map<String, String> defaults = new HashMap<>();
    
    switch (grade.toUpperCase()) {
        case "GS-14":
            defaults.put("1", "1-8"); defaults.put("2", "2-5"); defaults.put("3", "3-5");
            defaults.put("4", "4-5"); defaults.put("5", "5-6"); defaults.put("6", "6-4");
            defaults.put("7", "7-4"); defaults.put("8", "8-1"); defaults.put("9", "9-2");
            break;
        case "GS-13":
            defaults.put("1", "1-7"); defaults.put("2", "2-4"); defaults.put("3", "3-4");
            defaults.put("4", "4-5"); defaults.put("5", "5-5"); defaults.put("6", "6-3");
            defaults.put("7", "7-3"); defaults.put("8", "8-1"); defaults.put("9", "9-1");
            break;
        default: // GS-12
            defaults.put("1", "1-6"); defaults.put("2", "2-4"); defaults.put("3", "3-4");
            defaults.put("4", "4-4"); defaults.put("5", "5-4"); defaults.put("6", "6-3");
            defaults.put("7", "7-3"); defaults.put("8", "8-1"); defaults.put("9", "9-1");
    }
    
    return defaults;
}

/**
 * Get default factor points for a grade (fallback)
 */
private Map<String, Integer> getDefaultFactorPoints(String grade) {
    Map<String, String> levels = getDefaultFactorLevels(grade);
    Map<String, Integer> points = new HashMap<>();
    
    for (Map.Entry<String, String> entry : levels.entrySet()) {
        String factorNum = entry.getKey();
        String level = entry.getValue();
        points.put(factorNum, getPointsForLevel(factorNum, level));
    }
    
    return points;
}

/**
 * Helper methods to extract values from evaluation statement
 */
private String extractGradeFromEvaluation(String evaluation) {
    Pattern pattern = Pattern.compile("Final Grade:\\s*(GS-\\d+)");
    Matcher matcher = pattern.matcher(evaluation);
    return matcher.find() ? matcher.group(1) : null;
}

private String extractRangeFromEvaluation(String evaluation) {
    Pattern pattern = Pattern.compile("Grade Range:\\s*([\\d-]+)");
    Matcher matcher = pattern.matcher(evaluation);
    return matcher.find() ? matcher.group(1) : null;
}

private Integer extractPointsFromEvaluation(String evaluation) {
    Pattern pattern = Pattern.compile("Total Points:\\s*(\\d+)");
    Matcher matcher = pattern.matcher(evaluation);
    if (matcher.find()) {
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
    return null;
}

// Generate substantive rationale based on factor type and duties with grade-appropriate language
private String generateSubstantiveRationale(int factorNum, String level, String duties) {
    String lowerDuties = duties.toLowerCase();
    
    // Determine complexity level for appropriate language
    boolean isHighLevel = containsAny(lowerDuties, new String[]{"expert", "authority", "policy", "strategic", "executive", "agency-wide"});
    boolean isMidLevel = containsAny(lowerDuties, new String[]{"professional", "complex", "independent", "program", "coordinates"});
    
    switch (factorNum) {
        case 1:
            if (isHighLevel) {
                return "This position requires expert-level professional knowledge at level " + level + ". The duties demonstrate mastery of specialized principles, advanced analytical capabilities, and authoritative expertise needed to address complex organizational challenges and provide definitive guidance.";
            } else if (isMidLevel) {
                return "The duties require professional knowledge at level " + level + " involving independent application of complex concepts, analytical thinking, and specialized expertise to solve varied problems and make professional judgments.";
            } else {
                return "The position requires developing professional knowledge at level " + level + " to perform analytical work, apply established methods, and gain expertise in the field under appropriate guidance.";
            }
            
        case 2:
            if (level.equals("2-5")) {
                return "Supervisory controls at level " + level + " reflect extensive independence with administrative direction only. The incumbent sets priorities, plans work approaches, and operates with full authority for professional decisions within broad policy guidelines.";
            } else if (level.equals("2-4")) {
                return "The duties indicate considerable independence at level " + level + " with supervisor providing general guidance on priorities and approaches. The incumbent plans work, makes decisions, and operates with significant autonomy.";
            } else if (level.equals("2-3")) {
                return "Supervisory controls at level " + level + " provide specific guidance on complex assignments while allowing independence on routine work. The incumbent receives clear direction on new or difficult problems.";
            } else {
                return "The position operates under closer supervision at level " + level + " with regular guidance and review of work approaches and decisions.";
            }
            
        case 3:
            if (level.contains("5")) {
                return "Guidelines assessment at level " + level + " reflects the need to interpret broad policy, establish precedents, and develop new approaches where specific guidance doesn't exist. Extensive judgment required in applying principles.";
            } else if (level.contains("4")) {
                return "The duties require significant interpretation of guidelines at level " + level + " including adaptation of policies to new situations and development of approaches for complex problems.";
            } else {
                return "Guidelines at level " + level + " provide adequate direction for most work with some interpretation required for unusual situations or complex applications.";
            }
            
        case 4:
            if (level.contains("6") || level.contains("5")) {
                return "Work complexity at level " + level + " involves highly complex, unprecedented problems requiring innovative solutions, extensive analysis of interrelated factors, and creative problem-solving approaches.";
            } else if (level.contains("4")) {
                return "The duties present complex problems at level " + level + " requiring analytical thinking, consideration of multiple factors, and development of solutions for varied situations.";
            } else {
                return "Work complexity at level " + level + " involves moderately complex problems with some variety requiring analytical skills and problem-solving within established frameworks.";
            }
            
        case 5:
            if (level.contains("6")) {
                return "Scope and effect at level " + level + " demonstrates organization-wide and external impact affecting major policies, programs, and external stakeholder relationships with significant consequences for agency mission.";
            } else if (level.contains("5")) {
                return "The work scope at level " + level + " affects senior leadership decisions, major program directions, and organizational policies with substantial impact on agency operations and external relationships.";
            } else if (level.contains("4")) {
                return "Scope and effect at level " + level + " impacts multiple organizational units, program operations, and affects significant management decisions and resource allocation.";
            } else {
                return "The work scope at level " + level + " affects unit operations, project outcomes, and contributes to broader program objectives with moderate organizational impact.";
            }
            
        case 6:
            if (level.contains("4")) {
                return "Personal contacts at level " + level + " include senior executives, high-level external officials, key stakeholders, and industry leaders requiring sophisticated communication and relationship management skills.";
            } else if (level.contains("3")) {
                return "The position involves contacts at level " + level + " with management officials, external professionals, contractors, and stakeholders requiring diplomatic and professional interaction skills.";
            } else {
                return "Personal contacts at level " + level + " include staff throughout the organization, some external contacts, and various professional counterparts requiring effective communication skills.";
            }
            
        case 7:
            if (level.contains("4")) {
                return "Contact purposes at level " + level + " involve influencing policy decisions, negotiating critical agreements, resolving complex conflicts, and representing organizational positions on significant issues.";
            } else if (level.contains("3")) {
                return "The purpose of contacts at level " + level + " includes coordinating complex activities, influencing decisions, resolving problems, and providing authoritative information and guidance.";
            } else if (level.contains("2")) {
                return "Contact purposes at level " + level + " involve planning work, coordinating activities, exchanging information, and resolving routine problems through collaborative efforts.";
            } else {
                return "Contacts at level " + level + " primarily involve information exchange, clarification of requirements, and routine coordination activities.";
            }
            
        case 8:
            return "Physical demands at level " + level + " reflect typical professional office work with sedentary requirements, occasional travel, and standard technology use appropriate for analytical and administrative responsibilities.";
            
        case 9:
            if (level.contains("2")) {
                return "Work environment at level " + level + " involves moderate to high stress from deadlines, competing priorities, complex problem-solving demands, and significant responsibility for critical organizational outcomes.";
            } else {
                return "Work environment at level " + level + " represents normal office conditions with routine stress levels typical of professional analytical work and standard organizational demands.";
            }
            
        default:
            return "Factor level " + level + " assigned based on comprehensive analysis of position duties and requirements.";
    }
}

// Get intelligent factor level based on duties analysis with proper grade differentiation
private String getIntelligentLevel(int factorNum, String duties) {
    String lowerDuties = duties.toLowerCase();
    
    // Analyze overall complexity first to guide factor assignments
    boolean isGS15Level = containsAny(lowerDuties, new String[]{"nationally recognized", "preeminent", "government-wide", "pioneering", "industry leader"});
    boolean isGS14Level = containsAny(lowerDuties, new String[]{"subject matter expert", "recognized authority", "executive briefing", "agency-wide", "policy influence"});
    boolean isGS13Level = containsAny(lowerDuties, new String[]{"program manager", "policy development", "strategic planning", "organizational impact", "advises senior"});
    boolean isGS12Level = containsAny(lowerDuties, new String[]{"leads", "supervises", "program responsibility", "trains others", "manages projects"});
    boolean isGS11Level = containsAny(lowerDuties, new String[]{"independent professional", "complex analysis", "professional judgment", "coordinates", "plans work"});
    boolean isGS9Level = containsAny(lowerDuties, new String[]{"entry level", "developmental", "under guidance", "routine analysis", "follows established"});
    
    switch (factorNum) {
        case 1:
            if (isGS15Level) return "1-8";
            else if (isGS14Level) return "1-7";
            else if (isGS13Level) return "1-6";
            else if (isGS12Level) return "1-5";
            else if (isGS11Level) return "1-5";
            else if (isGS9Level) return "1-4";
            else return "1-4";
            
        case 2:
            if (isGS15Level || isGS14Level) return "2-5";
            else if (isGS13Level || isGS12Level) return "2-4";
            else if (isGS11Level) return "2-3";
            else if (isGS9Level) return "2-2";
            else return "2-3";
            
        case 3:
            if (isGS15Level) return "3-5";
            else if (isGS14Level || isGS13Level) return "3-4";
            else if (isGS12Level || isGS11Level) return "3-3";
            else return "3-2";
            
        case 4:
            if (isGS15Level) return "4-6";
            else if (isGS14Level) return "4-5";
            else if (isGS13Level || isGS12Level) return "4-4";
            else if (isGS11Level) return "4-3";
            else return "4-2";
            
        case 5:
            if (isGS15Level) return "5-6";
            else if (isGS14Level) return "5-5";
            else if (isGS13Level) return "5-4";
            else if (isGS12Level) return "5-3";
            else if (isGS11Level) return "5-3";
            else return "5-2";
            
        case 6:
            if (isGS15Level || isGS14Level) return "6-4";
            else if (isGS13Level || isGS12Level) return "6-3";
            else return "6-2";
            
        case 7:
            if (isGS15Level || isGS14Level) return "7-4";
            else if (isGS13Level || isGS12Level) return "7-3";
            else if (isGS11Level) return "7-2";
            else return "7-1";
            
        case 8:
            return "8-1";
            
        case 9:
            if (isGS15Level || isGS14Level || isGS13Level) return "9-2";
            else return "9-1";
            
        default:
            return "1-1";
    }
}

// Helper method to check if string contains any of the target phrases
private boolean containsAny(String text, String[] phrases) {
    for (String phrase : phrases) {
        if (text.contains(phrase)) {
            return true;
        }
    }
    return false;
}

// Adjust points to avoid forbidden grade ranges (GS-6: 1105-1350, GS-8: 1605-1850, GS-10: 2105-2350)
private int adjustForForbiddenGrades(int totalPoints, Map<String, Object> validatedFactors,
                                Map<String, Map<String, Integer>> FACTOR_POINTS) {
    if ((totalPoints >= 1105 && totalPoints <= 1350) || 
        (totalPoints >= 1605 && totalPoints <= 1850) || 
        (totalPoints >= 2105 && totalPoints <= 2350)) {
        
        if (totalPoints <= 1350) totalPoints = 1355;
        else if (totalPoints <= 1850) totalPoints = 1855;
        else totalPoints = 2355;

        @SuppressWarnings("unchecked")
        Map<String, Object> factor1 = (Map<String, Object>) validatedFactors.get("Factor 1");
        String currentLevel = (String) factor1.get("level");
        String newLevel = getNextHigherLevel(currentLevel, FACTOR_POINTS.get("1"));
        
        if (newLevel != null) {
            Integer newPoints = FACTOR_POINTS.get("1").get(newLevel);
            Integer currentPoints = (Integer) factor1.get("points");
            factor1.put("level", newLevel);
            factor1.put("points", newPoints);
            factor1.put("rationale", factor1.get("rationale"));
            return totalPoints + (newPoints - currentPoints);
        }
    }
    
    return totalPoints;
}

// Helper method to find closest valid level
private String findClosestValidLevel(String factorNum, String invalidLevel, Map<String, Integer> validLevels) {
    try {
        String[] parts = invalidLevel.split("-");
        if (parts.length == 2) {
            int levelNum = Integer.parseInt(parts[1]);
            String prefix = parts[0];
            
            int minDiff = Integer.MAX_VALUE;
            String closestLevel = null;
            
            for (String validLevel : validLevels.keySet()) {
                String[] validParts = validLevel.split("-");
                if (validParts.length == 2 && validParts[0].equals(prefix)) {
                    int validNum = Integer.parseInt(validParts[1]);
                    int diff = Math.abs(levelNum - validNum);
                    if (diff < minDiff) {
                        minDiff = diff;
                        closestLevel = validLevel;
                    }
                }
            }
            
            if (closestLevel != null) {
                return closestLevel;
            }
        }
    } catch (Exception e) {
        // Fall back to default
    }
    
    return validLevels.keySet().iterator().next();
}

@PostMapping("/generate-title-series")
public Map<String, String> generateTitleSeriesDetermination(@RequestBody Map<String, String> body) throws Exception {
    String duties = body.getOrDefault("duties", "");
    String gsGrade = body.getOrDefault("gsGrade", "");
    String jobSeries = body.getOrDefault("jobSeries", "");
    String jobTitle = body.getOrDefault("jobTitle", "");
    String supervisoryLevel = body.getOrDefault("supervisoryLevel", "Non-Supervisory");

    // Short, focused prompt with keyword guidance for supervisory fit
    String prompt = String.format("""
    Duties may be listed with percentages. Duties with higher percentages must be given greater weight and considered more important in your analysis.
    You are a federal HR classification specialist.

    Evaluate the following position details and determine the most appropriate federal job series and title based on the duties provided. Weigh higher-percentage duties more heavily.

    Duties: %s
    GS Grade: %s
    Proposed Job Series: %s
    Proposed Job Title: %s
    Supervisory Level: %s

    When evaluating if the supervisory level fits, use these keyword indicators:
    - **Supervisory**: supervises, manages, directs, oversees, assigns work, evaluates performance, hires, fires, conducts reviews, approves leave, sets team goals, responsible for staff, team lead, leadership, coaching, mentoring, staff development, resource allocation, disciplinary actions, performance appraisals, hiring, onboarding, delegating tasks, managing teams, conducting meetings, providing feedback, handling grievances, employee relations, policy implementation, compliance oversight, budget management, project management, stakeholder engagement, organizational development, performance improvement, process improvement, quality assurance, compliance, audit, risk management, strategic planning.
    - **Non-Supervisory**: performs tasks, provides support, assists, completes assignments, individual contributor, technical work, analysis, research, reporting, documentation, customer service, data entry, maintains records, prepares reports, follows procedures, supports team, executes tasks, implements, monitors, coordinates, communicates, collaborates, operates equipment, maintains systems, provides information, reviews documents, collects data, analyzes data, prepares materials, drafts correspondence, schedules, organizes, files, tracks, updates, reviews, responds, assists customers, provides technical support.

    Your response must include:
    - **Best-Fitting Series and Title:** State the recommended job series and title based on OPM standards.
    - **Evaluation:** Clearly state if the proposed job series is appropriate or not, and explain why.
    - **Supervisory Level Fit:** Clearly state if the chosen supervisory level is appropriate for the duties described, and explain why or why not, referencing the keywords above.
    - **Explanation:** Summarize your reasoning and justification in 2–3 sentences.

    Format your response as follows:

    **Title and Series Determination**

    **Best-Fitting Series and Title:**  
    [Recommended job series and title]

    **Evaluation:**  
    [Is the proposed job series appropriate? Why or why not?]

    **Supervisory Level Fit:**  
    [Is the chosen supervisory level appropriate for the duties? Why or why not? Reference the keywords.]

    **Explanation:**  
    [2–3 sentences summarizing your reasoning and justification.]
    """, duties, gsGrade, jobSeries, jobTitle, supervisoryLevel);

        String response = callOpenAIWithTimeout(prompt, 20);
        return Map.of("titleSeriesSection", response.trim());
    }

@GetMapping("/proxy/usajobs")
public ResponseEntity<String> proxyUsaJobs(@RequestParam Map<String, String> params) {
    try {
        if (usajobsApiKey == null || usajobsApiKey.isBlank() || usajobsUserAgent == null || usajobsUserAgent.isBlank()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"USAJobs headers not configured\"}");
        }

        Map<String, String> safeParams = new LinkedHashMap<>(params);
        int desiredRpp = 0;
        try { desiredRpp = Integer.parseInt(safeParams.getOrDefault("ResultsPerPage", "0")); } catch (NumberFormatException ignore) {}

        if (desiredRpp > 500) {
            return aggregateUsaJobs(safeParams, desiredRpp);
        }
        if (desiredRpp <= 0 || desiredRpp > 500) {
            safeParams.put("ResultsPerPage", "500");
        }

        StringBuilder url = new StringBuilder("https://data.usajobs.gov/api/search?");
        safeParams.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                url.append(k).append("=").append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append("&");
            }
        });
        String finalUrl = url.toString();
        if (finalUrl.endsWith("&")) finalUrl = finalUrl.substring(0, finalUrl.length() - 1);

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization-Key", usajobsApiKey);
        headers.set("User-Agent", usajobsUserAgent);
        headers.set("Accept", "application/json");
        HttpEntity<?> entity = new HttpEntity<>(headers);

        ResponseEntity<String> upstream = restTemplate.exchange(finalUrl, HttpMethod.GET, entity, String.class);

        // ONLY return 503 if USAJobs itself returns 5xx or 429
        if (upstream.getStatusCode().is5xxServerError() || upstream.getStatusCode().value() == 429) {
            System.err.println("USAJobs returned error status: " + upstream.getStatusCode().value());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"SearchResult\":{\"SearchResultItems\":[],\"SearchResultCount\":0},\"warning\":\"USAJobs unavailable ("
                            + upstream.getStatusCode().value() + ")\"}");
        }

        String body = upstream.getBody();

        // Only check if body is completely empty
        if (body == null || body.trim().isEmpty()) {
            System.err.println("USAJobs returned empty body");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"SearchResult\":{\"SearchResultItems\":[],\"SearchResultCount\":0},\"warning\":\"USAJobs returned empty response\"}");
        }

        // Check if body is HTML error page (starts with <!DOCTYPE or <html)
        String trimmedBody = body.trim();
        if (trimmedBody.startsWith("<!DOCTYPE") || trimmedBody.startsWith("<html") || trimmedBody.startsWith("<HTML")) {
            System.err.println("USAJobs returned HTML instead of JSON");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"SearchResult\":{\"SearchResultItems\":[],\"SearchResultCount\":0},\"warning\":\"USAJobs returned HTML error page\"}");
        }

        // Safe dedupe (but don't fail if dedupe has issues - just return original)
        String safeBody = body;
        try {
            safeBody = dedupeUsaJobsTitlesCaseInsensitive(body);
        } catch (Exception ex) {
            System.err.println("USAJobs JSON dedupe warning: " + ex.getMessage());
            // Don't return 503 - just log and continue with original body
        }

        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(safeBody);

    } catch (HttpClientErrorException | HttpServerErrorException e) {
        org.springframework.http.HttpStatusCode statusCode = e.getStatusCode();
        int statusValue = statusCode.value();
        
        // ONLY return 503 for actual server errors from USAJobs
        if (statusCode.is5xxServerError() || statusValue == 429) {
            System.err.println("USAJobs HTTP error: " + statusValue);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"SearchResult\":{\"SearchResultItems\":[],\"SearchResultCount\":0},\"warning\":\"USAJobs unavailable (" + statusValue + ")\"}");
        }
        
        // For 4xx errors, pass through the actual status
        return ResponseEntity.status(statusCode)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"USAJobs API error\",\"status\":" + statusValue +
                      ",\"message\":\"" + e.getMessage().replace("\"","'") + "\"}");
    } catch (Exception e) {
        System.err.println("USAJobs proxy unexpected error: " + e.getMessage());
        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"Internal server error\",\"message\":\"" + e.getMessage().replace("\"","'") + "\"}");
    }
}

private ResponseEntity<String> aggregateUsaJobs(Map<String, String> baseParams, int desiredRpp) {
    try {
        ObjectMapper m = new ObjectMapper();
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization-Key", usajobsApiKey);
        headers.set("User-Agent", usajobsUserAgent);
        headers.set("Accept", "application/json");
        HttpEntity<?> entity = new HttpEntity<>(headers);

        Map<String, String> common = new LinkedHashMap<>(baseParams);
        common.remove("ResultsPerPage");
        int startPage = Integer.parseInt(common.getOrDefault("Page", "1"));
        common.remove("Page");

        int needed = Math.max(1, desiredRpp);
        int perPage = 500;
        int page = startPage;

        ArrayNode aggregatedItems = m.createArrayNode();
        Set<String> seenIds = new HashSet<>();

        JsonNode firstRoot = null;
        int safety = 0;

        while (needed > 0 && safety++ < 50) {
            StringBuilder url = new StringBuilder("https://data.usajobs.gov/api/search?");
            common.forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    url.append(k).append("=").append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append("&");
                }
            });
            url.append("ResultsPerPage=").append(perPage).append("&Page=").append(page);

            ResponseEntity<String> resp = restTemplate.exchange(url.toString(), HttpMethod.GET, entity, String.class);

            // ONLY return 503 if USAJobs returns actual server error
            if (resp.getStatusCode().is5xxServerError() || resp.getStatusCode().value() == 429) {
                System.err.println("USAJobs aggregation error: " + resp.getStatusCode().value());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"SearchResult\":{\"SearchResultItems\":[],\"SearchResultCount\":0},\"warning\":\"USAJobs unavailable (" + resp.getStatusCode().value() + ")\"}");
            }

            String body = resp.getBody() == null ? "" : resp.getBody();

            // Only check for HTML error pages
            if (body.trim().startsWith("<!DOCTYPE") || body.trim().startsWith("<html") || body.trim().startsWith("<HTML")) {
                System.err.println("USAJobs returned HTML during aggregation");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"SearchResult\":{\"SearchResultItems\":[],\"SearchResultCount\":0},\"warning\":\"USAJobs returned HTML error\"}");
            }

            JsonNode root;
            try { 
                root = m.readTree(body); 
            } catch (Exception parseEx) {
                System.err.println("USAJobs JSON parse error during aggregation: " + parseEx.getMessage());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"SearchResult\":{\"SearchResultItems\":[],\"SearchResultCount\":0},\"warning\":\"USAJobs JSON parse error\"}");
            }

            if (firstRoot == null) firstRoot = root.deepCopy();

            JsonNode items = root.path("SearchResult").path("SearchResultItems");
            if (!items.isArray() || items.size() == 0) break;

            for (JsonNode item : items) {
                if (needed <= 0) break;
                String id = item.path("MatchedObjectId").asText("");
                if (id.isEmpty() || seenIds.add(id)) {
                    aggregatedItems.add(item);
                    needed--;
                }
            }

            int total = root.path("SearchResult").path("SearchResultCount").asInt(0);
            if ((page * perPage) >= total) break;
            page++;
        }

        if (firstRoot == null) {
            System.err.println("USAJobs aggregation: no data received");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"SearchResult\":{\"SearchResultItems\":[],\"SearchResultCount\":0},\"warning\":\"USAJobs empty aggregation\"}");
        }

        ObjectNode combined = (ObjectNode) firstRoot.deepCopy();
        ObjectNode sr = (ObjectNode) combined.path("SearchResult");
        if (sr == null || sr.isMissingNode()) {
            sr = m.createObjectNode();
            combined.set("SearchResult", sr);
        }
        sr.set("SearchResultItems", aggregatedItems);
        sr.put("SearchResultCountReturned", aggregatedItems.size());

        String out = m.writeValueAsString(combined);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(out);

    } catch (Exception e) {
        System.err.println("USAJobs aggregation failed: " + e.getMessage());
        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"SearchResult\":{\"SearchResultItems\":[],\"SearchResultCount\":0},\"warning\":\"USAJobs aggregation failed: " + e.getMessage() + "\"}");
    }
}

private String normalizeUsaJobsTitle(String title) {
    if (title == null) return "";
    String s = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKC);
    // remove parenthetical/bracketed qualifiers
    s = s.replaceAll("\\s*[\\(\\[{][^\\)\\]}]*[\\)\\]}]\\s*", " ");
    // remove commas and tighten hyphen spacing
    s = s.replace(",", " ").replaceAll("\\s*-\\s*", "-");
    // collapse whitespace and lower for key
    s = s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    return s;
}

private String dedupeUsaJobsTitlesCaseInsensitive(String json) {
    if (json == null || json.isBlank()) return json;
    try {
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(json);
        JsonNode sr = root.get("SearchResult");
        if (!(sr instanceof ObjectNode)) return json;
        JsonNode items = sr.get("SearchResultItems");
        if (!(items instanceof ArrayNode)) return json;

        Map<String, JsonNode> seen = new LinkedHashMap<>();
        for (JsonNode item : items) {
            String title = item.path("MatchedObjectDescriptor").path("PositionTitle").asText("");
            String key = normalizeUsaJobsTitle(title);
            if (key.isEmpty()) continue;
            // keep first occurrence of normalized title
            seen.putIfAbsent(key, item);
        }

        ArrayNode newItems = m.createArrayNode();
        seen.values().forEach(newItems::add);
        ((ObjectNode) sr).set("SearchResultItems", newItems);

        if (sr.has("SearchResultCount") && sr.get("SearchResultCount").isInt()) {
            ((ObjectNode) sr).put("SearchResultCount", newItems.size());
        }
        return m.writeValueAsString(root);
    } catch (Exception e) {
        System.err.println("Failed to dedupe USAJobs titles: " + e.getMessage());
        return json;
    }
}

private String getNextHigherValidLevel(String factorNum, String currentLevel) {
    String[] parts = currentLevel.split("-");
    if (parts.length != 2) return null;
    
    try {
        int currentNum = Integer.parseInt(parts[1]);
        int maxLevel = getMaxLevelForFactor(factorNum);
        
        if (currentNum < maxLevel) {
            return factorNum + "-" + (currentNum + 1);
        }
    } catch (NumberFormatException e) {
        return null;
    }
    
    return null;
}

private String getNextLowerValidLevel(String factorNum, String currentLevel) {
    String[] parts = currentLevel.split("-");
    if (parts.length != 2) return null;
    
    try {
        int currentNum = Integer.parseInt(parts[1]);
        
        if (currentNum > 1) {
            return factorNum + "-" + (currentNum - 1);
        }
    } catch (NumberFormatException e) {
        return null;
    }
    
    return null;
}

private String preserveFactorLetter(String oldLevel, String newLevel) {
    // If oldLevel is like "7-D" and newLevel is like "7-2", convert newLevel to "7-B"
    if (oldLevel != null && oldLevel.matches("\\d+-[A-Z]") && newLevel != null && newLevel.matches("\\d+-\\d+")) {
        int num = Integer.parseInt(newLevel.split("-")[1]);
        // Map 1->A, 2->B, 3->C, 4->D, etc.
        char letter = (char) ('A' + num - 1);
        return newLevel.split("-")[0] + "-" + letter;
    }
    // If oldLevel is like "7-2" and newLevel is like "7-D", convert newLevel to "7-2"
    if (oldLevel != null && oldLevel.matches("\\d+-\\d+") && newLevel != null && newLevel.matches("\\d+-[A-Z]")) {
        char letter = newLevel.split("-")[1].charAt(0);
        int num = (letter - 'A') + 1;
        return newLevel.split("-")[0] + "-" + num;
    }
    // Otherwise, keep newLevel as is
    return newLevel;
}

/**
 * Return the human-readable factor name for a given factor number.
 */
private String getFactorName(int i) {
    switch (i) {
        case 1: return "Knowledge Required by the Position";
        case 2: return "Supervisory Controls";
        case 3: return "Guidelines";
        case 4: return "Complexity";
        case 5: return "Scope and Effect";
        case 6: return "Personal Contacts";
        case 7: return "Purpose of Contacts";
        case 8: return "Physical Demands";
        case 9: return "Work Environment";
        default: return "Factor " + i;
    }
}

@PostMapping("/upload-pdf")
public ResponseEntity<Map<String, String>> uploadPdf(@RequestParam("file") MultipartFile file,
                                                @RequestParam(value = "type", defaultValue = "reference") String type) {
    try {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file provided"));
        }

        // Validate file type
        String contentType = file.getContentType();
        if (contentType == null || !contentType.equals("application/pdf")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only PDF files are allowed"));
        }

        // Create pdfs directory if it doesn't exist
        File pdfDir = new File("src/main/resources/pdfs");
        if (!pdfDir.exists()) {
            pdfDir.mkdirs();
        }

        // Generate unique filename with type prefix
        String originalFilename = file.getOriginalFilename();
        String timestamp = String.valueOf(System.currentTimeMillis());
        String filename = type + "-" + timestamp + "-" + originalFilename;
        
        // Save file
        File destinationFile = new File(pdfDir, filename);
        file.transferTo(destinationFile);

        // Extract text preview for validation
        String textPreview = extractPdfText(destinationFile.getAbsolutePath());
        String preview = textPreview.length() > 500 ? 
            textPreview.substring(0, 500) + "..." : textPreview;

        System.out.println("PDF uploaded successfully: " + filename);
        System.out.println("Text preview: " + preview);

        return ResponseEntity.ok(Map.of(
            "message", "PDF uploaded successfully",
            "filename", filename,
            "type", type,
            "size", String.valueOf(file.getSize()),
            "textPreview", preview
        ));

    } catch (Exception e) {
        System.err.println("Error uploading PDF: " + e.getMessage());
        return ResponseEntity.status(500).body(Map.of("error", "Failed to upload PDF: " + e.getMessage()));
    }
}

@PostMapping("/generate-common-duty")
public Map<String, Object> generateCommonDuty(@RequestBody Map<String, String> body) throws Exception {
    String jobSeries = body.getOrDefault("jobSeries", "");
    String positionTitle = body.getOrDefault("positionTitle", "");
    int count = Integer.parseInt(body.getOrDefault("count", "1")); // Default to 1 duty
    
    if (!"0343".equals(jobSeries) || positionTitle.isBlank()) {
        return Map.of("duties", new ArrayList<>());
    }
    
    // Generate multiple duties for 0343
    String prompt = String.format(
        "You are an expert in federal HR classification. " +
        "Write %d specific, unique, and commonly expected duties for a position in the 0343 series (Management and Program Analyst) " +
        "with the title '%s'. Each duty must be clear, actionable, and reflect core responsibilities for this series and title. " +
        "Return ONLY the duties as a numbered list (1. ... 2. ...), no explanation, no extra text.",
        count,
        positionTitle
    );
    
    String response = callOpenAIWithTimeout(prompt, 15);
    
    // Parse numbered list from response
    List<String> duties = new ArrayList<>();
    String[] lines = response.trim().split("\n");
    
    for (String line : lines) {
        // Match lines like "1. Duty text" or "1) Duty text"
        String cleaned = line.replaceAll("^\\d+[.)\\s]+", "").trim();
        if (!cleaned.isEmpty()) {
            duties.add(cleaned);
        }
    }
    
    // Ensure we have exactly the requested count
    while (duties.size() < count) {
        duties.add("");
    }
    
    // Return structured response
    Map<String, Object> result = new HashMap<>();
    result.put("duties", duties.subList(0, Math.min(count, duties.size())));
    
    return result;
}

/**
 * List available PDF files
 */
@GetMapping("/list-pdfs")
public ResponseEntity<Map<String, Object>> listPdfs() {
    try {
        File pdfDir = new File("src/main/resources/pdfs");
        if (!pdfDir.exists()) {
            return ResponseEntity.ok(Map.of("pdfs", new ArrayList<>(), "message", "No PDF directory found"));
        }

        File[] pdfFiles = pdfDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
        List<Map<String, Object>> pdfList = new ArrayList<>();

        if (pdfFiles != null) {
            for (File pdf : pdfFiles) {
                Map<String, Object> pdfInfo = new HashMap<>();
                pdfInfo.put("filename", pdf.getName());
                pdfInfo.put("size", pdf.length());
                pdfInfo.put("lastModified", new Date(pdf.lastModified()));
                
                // Determine type from filename prefix
                String filename = pdf.getName();
                if (filename.startsWith("omp-")) pdfInfo.put("type", "OMP Standards");
                else if (filename.startsWith("factor-")) pdfInfo.put("type", "Factor Evaluation");
                else if (filename.startsWith("series-")) pdfInfo.put("type", "Series Standards");
                else pdfInfo.put("type", "Reference");
                
                pdfList.add(pdfInfo);
            }
        }

        return ResponseEntity.ok(Map.of(
            "pdfs", pdfList,
            "total", pdfList.size()
        ));

    } catch (Exception e) {
        return ResponseEntity.status(500).body(Map.of("error", "Failed to list PDFs: " + e.getMessage()));
    }
}

/**
 * Delete a PDF file
 */
@DeleteMapping("/delete-pdf/{filename}")
public ResponseEntity<Map<String, String>> deletePdf(@PathVariable String filename) {
    try {
        File pdfFile = new File("src/main/resources/pdfs/" + filename);
        if (!pdfFile.exists()) {
            return ResponseEntity.notFound().build();
        }

        if (pdfFile.delete()) {
            return ResponseEntity.ok(Map.of("message", "PDF deleted successfully", "filename", filename));
        } else {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to delete PDF"));
        }

    } catch (Exception e) {
        return ResponseEntity.status(500).body(Map.of("error", "Failed to delete PDF: " + e.getMessage()));
    }
}

}