package net.youssfi.transactionservice.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Implémentation simple d'EmbeddingModel utilisant Ollama
 * Note: Cette implémentation utilise l'API Ollama directement pour générer des embeddings
 */
@Component
@Slf4j
public class OllamaEmbeddingModelImpl implements EmbeddingModel {

    @Value("${ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${ollama.embedding-model-name:nomic-embed-text}")
    private String embeddingModelName;

    private final HttpClient httpClient;
    private final Gson gson;

    public String getEmbeddingModelName() {
        return embeddingModelName;
    }

    public OllamaEmbeddingModelImpl() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.gson = new Gson();
    }

    @Override
    public Response<Embedding> embed(String text) {
        try {
            // Utiliser Gson pour créer le JSON correctement (gère automatiquement l'échappement)
            JsonObject requestJson = new JsonObject();
            requestJson.addProperty("model", embeddingModelName);
            requestJson.addProperty("prompt", text);
            String requestBody = gson.toJson(requestJson);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/embeddings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // Parser la réponse JSON pour extraire l'embedding
                String responseBody = response.body();
                // Extraction simple de l'embedding depuis la réponse JSON
                // Format attendu: {"embedding": [0.1, 0.2, ...]}
                float[] embeddingValues = parseEmbeddingFromJson(responseBody);
                
                if (embeddingValues != null && embeddingValues.length > 0) {
                    Embedding embedding = Embedding.from(embeddingValues);
                    return Response.from(embedding);
                } else {
                    log.error("Impossible de parser l'embedding depuis la réponse Ollama");
                    return createFallbackEmbedding(text);
                }
            } else {
                log.warn("Erreur lors de l'appel à Ollama: status={}, body={}", response.statusCode(), response.body());
                return createFallbackEmbedding(text);
            }
        } catch (Exception e) {
            log.error("Erreur lors de la génération de l'embedding: {}", e.getMessage(), e);
            return createFallbackEmbedding(text);
        }
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<Embedding> embeddings = new ArrayList<>();
        for (TextSegment segment : textSegments) {
            String text = segment.text();
            Response<Embedding> response = embed(text);
            embeddings.add(response.content());
        }
        return Response.from(embeddings);
    }

    /**
     * Parse l'embedding depuis la réponse JSON d'Ollama
     */
    private float[] parseEmbeddingFromJson(String json) {
        try {
            // Extraction simple: chercher le tableau "embedding"
            // Format attendu: {"embedding": [0.1, 0.2, ...]}
            int startIndex = json.indexOf("\"embedding\":[");
            if (startIndex == -1) {
                // Essayer un autre format possible
                startIndex = json.indexOf("\"embedding\" : [");
                if (startIndex == -1) {
                    log.warn("Format d'embedding non reconnu dans la réponse Ollama");
                    return null;
                }
                startIndex += 15;
            } else {
                startIndex += 13; // longueur de "\"embedding\":["
            }
            
            int endIndex = json.indexOf("]", startIndex);
            if (endIndex == -1) {
                log.warn("Impossible de trouver la fin du tableau embedding");
                return null;
            }
            
            String arrayStr = json.substring(startIndex, endIndex);
            String[] values = arrayStr.split(",");
            float[] embedding = new float[values.length];
            for (int i = 0; i < values.length; i++) {
                String value = values[i].trim();
                embedding[i] = Float.parseFloat(value);
            }
            log.debug("Embedding parsé avec succès: {} dimensions", embedding.length);
            return embedding;
        } catch (Exception e) {
            log.error("Erreur lors du parsing de l'embedding: {}", e.getMessage());
            log.debug("JSON reçu: {}", json);
            return null;
        }
    }

    /**
     * Crée un embedding de fallback simple basé sur le hash du texte
     * Ceci est utilisé si Ollama n'est pas disponible
     * IMPORTANT: Utiliser 768 dimensions pour correspondre à nomic-embed-text
     */
    private Response<Embedding> createFallbackEmbedding(String text) {
        // Embedding simple basé sur le hash du texte (768 dimensions pour correspondre à nomic-embed-text)
        float[] embedding = new float[768];
        int hash = text.hashCode();
        for (int i = 0; i < 768; i++) {
            embedding[i] = (float) Math.sin(hash + i) * 0.1f;
        }
        log.warn("⚠️ Utilisation d'un embedding de fallback (768 dimensions) - Ollama n'est pas disponible ou a échoué");
        return Response.from(Embedding.from(embedding));
    }
}
