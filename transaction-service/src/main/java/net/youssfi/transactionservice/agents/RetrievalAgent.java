package net.youssfi.transactionservice.agents;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Agent de récupération qui cherche dans les documents
 * Fait partie de l'architecture Agentic RAG 2.0
 */
@Component
@Slf4j
public class RetrievalAgent {
    
    @Autowired(required = false)
    private EmbeddingStore<TextSegment> embeddingStore;
    
    @Autowired(required = false)
    private EmbeddingModel embeddingModel;
    
    @Value("${rag.retriever.max-results:30}")
    private int maxResults;
    
    @Value("${rag.retriever.min-score:0.0}")
    private double minScore;
    
    /**
     * Cherche dans les documents et retourne le contexte RAG
     * 
     * @param question La question de l'utilisateur
     * @return Contexte RAG formaté
     */
    public String search(String question) {
        if (embeddingStore == null || embeddingModel == null) {
            log.warn("⚠️ EmbeddingStore ou EmbeddingModel non disponible");
            return "";
        }
        
        try {
            // Générer l'embedding de la question
            Embedding queryEmbedding = embeddingModel.embed(question).content();
            
            // Rechercher dans le vector store
            Method findRelevantMethod = embeddingStore.getClass()
                .getMethod("findRelevant", Embedding.class, int.class, double.class);
            
            @SuppressWarnings("unchecked")
            List<dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment>> matches = 
                (List<dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment>>) 
                findRelevantMethod.invoke(embeddingStore, queryEmbedding, maxResults, minScore);
            
            if (matches == null || matches.isEmpty()) {
                log.warn("⚠️ Aucun résultat trouvé");
                return "";
            }
            
            // Formater le contexte
            StringBuilder context = new StringBuilder();
            context.append("═══════════════════════════════════════════════════════════\n");
            context.append("📚 CONTEXTE PERTINENT DEPUIS LES DOCUMENTS\n");
            context.append("═══════════════════════════════════════════════════════════\n\n");
            
            for (int i = 0; i < matches.size(); i++) {
                dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment> match = matches.get(i);
                context.append(String.format("【 Extrait %d 】 (Score: %.3f)\n", i + 1, match.score()));
                context.append(match.embedded().text());
                context.append("\n\n");
            }
            
            log.info("✅ {} segments trouvés", matches.size());
            return context.toString();
            
        } catch (Exception e) {
            log.error("❌ Erreur lors de la recherche: {}", e.getMessage(), e);
            return "";
        }
    }
}
