package net.youssfi.transactionservice.agents;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Agent de raisonnement qui interprète et structure le contexte
 * Fait partie de l'architecture Agentic RAG 2.0
 */
@Component
@Slf4j
public class ReasoningAgent {
    
    private final ChatLanguageModel chatLanguageModel;
    
    public ReasoningAgent(ChatLanguageModel chatLanguageModel) {
        this.chatLanguageModel = chatLanguageModel;
    }
    
    /**
     * Contexte structuré après interprétation
     */
    public static class StructuredContext {
        private final String intent; // Intention extraite
        private final String structuredContext; // Contexte structuré
        private final String responseTemplate; // Template de réponse suggéré
        private final String keyPoints; // Points clés extraits
        
        public StructuredContext(String intent, String structuredContext, 
                                String responseTemplate, String keyPoints) {
            this.intent = intent;
            this.structuredContext = structuredContext;
            this.responseTemplate = responseTemplate;
            this.keyPoints = keyPoints;
        }
        
        public String getIntent() { return intent; }
        public String getStructuredContext() { return structuredContext; }
        public String getResponseTemplate() { return responseTemplate; }
        public String getKeyPoints() { return keyPoints; }
    }
    
    /**
     * Interprète la question et structure le contexte pour une meilleure génération
     * 
     * @param question La question de l'utilisateur
     * @param ragContext Le contexte RAG (peut être vide)
     * @param toolResult Le résultat des outils DB (peut être null)
     * @return Contexte structuré
     */
    public StructuredContext interpretAndStructure(String question, 
                                                   String ragContext, 
                                                   String toolResult) {
        log.info("🧠 ReasoningAgent: Début de l'interprétation et structuration");
        log.debug("   Question: {}", question);
        
        try {
            // 1. Extraire l'intention
            String intent = extractIntent(question);
            log.debug("   Intention extraite: {}", intent);
            
            // 2. Structurer le contexte
            String structuredContext = structureContext(question, ragContext, toolResult);
            log.debug("   Contexte structuré ({} caractères)", structuredContext.length());
            
            // 3. Extraire les points clés
            String keyPoints = extractKeyPoints(question, ragContext, toolResult);
            log.debug("   Points clés extraits");
            
            // 4. Suggérer un template de réponse
            String responseTemplate = suggestResponseTemplate(intent, keyPoints);
            log.debug("   Template de réponse suggéré");
            
            log.info("✅ ReasoningAgent: Structuration terminée");
            
            return new StructuredContext(
                intent,
                structuredContext,
                responseTemplate,
                keyPoints
            );
            
        } catch (Exception e) {
            log.error("❌ Erreur lors de l'interprétation: {}", e.getMessage(), e);
            // Retourner un contexte structuré minimal en cas d'erreur
            return new StructuredContext(
                "unknown",
                ragContext != null ? ragContext : (toolResult != null ? toolResult : ""),
                "Répondre de manière claire et structurée",
                ""
            );
        }
    }
    
    /**
     * Extrait l'intention de la question
     */
    private String extractIntent(String question) {
        String prompt = String.format("""
            Analyse la question suivante et identifie l'intention principale.
            Réponds UNIQUEMENT par l'intention en une phrase courte.
            
            QUESTION:
            %s
            
            INTENTION:
            """, question);
        
        try {
            Response<dev.langchain4j.data.message.AiMessage> response = chatLanguageModel.generate(
                SystemMessage.from("Tu es un expert en analyse d'intentions. Réponds UNIQUEMENT par l'intention en une phrase courte."),
                UserMessage.from(prompt)
            );
            
            return response.content().text().trim();
            
        } catch (Exception e) {
            log.warn("Erreur lors de l'extraction d'intention: {}", e.getMessage());
            return "Répondre à la question de l'utilisateur";
        }
    }
    
    /**
     * Structure le contexte de manière optimale pour la génération
     */
    private String structureContext(String question, String ragContext, String toolResult) {
        StringBuilder structured = new StringBuilder();
        
        structured.append("═══════════════════════════════════════════════════════════\n");
        structured.append("📋 CONTEXTE STRUCTURÉ POUR LA GÉNÉRATION\n");
        structured.append("═══════════════════════════════════════════════════════════\n\n");
        
        // Section RAG
        if (ragContext != null && !ragContext.isEmpty()) {
            structured.append("📚 INFORMATIONS DES DOCUMENTS:\n");
            structured.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            
            // Nettoyer et structurer le contexte RAG
            String cleanedRag = ragContext
                .replace("═══════════════════════════════════════════════════════════", "")
                .replace("📚 CONTEXTE PERTINENT DEPUIS LES DOCUMENTS CHARGÉS", "")
                .trim();
            
            structured.append(cleanedRag);
            structured.append("\n\n");
        }
        
        // Section DB
        if (toolResult != null && !toolResult.isEmpty()) {
            structured.append("💾 DONNÉES DE LA BASE DE DONNÉES:\n");
            structured.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            structured.append(toolResult);
            structured.append("\n\n");
        }
        
        // Instructions
        structured.append("📝 INSTRUCTIONS:\n");
        structured.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        if (ragContext != null && !ragContext.isEmpty() && toolResult == null) {
            structured.append("- Utiliser UNIQUEMENT les informations des documents ci-dessus\n");
            structured.append("- Ne pas mentionner la base de données\n");
        } else if (toolResult != null && (ragContext == null || ragContext.isEmpty())) {
            structured.append("- Utiliser UNIQUEMENT les données de la base de données ci-dessus\n");
            structured.append("- Ne pas mentionner les documents\n");
        } else {
            structured.append("- Utiliser les informations pertinentes du contexte ci-dessus\n");
        }
        structured.append("- Répondre de manière claire et structurée\n");
        structured.append("- Citer les sources quand c'est pertinent\n");
        
        return structured.toString();
    }
    
    /**
     * Extrait les points clés du contexte
     */
    private String extractKeyPoints(String question, String ragContext, String toolResult) {
        String contextToAnalyze = "";
        if (ragContext != null && !ragContext.isEmpty()) {
            contextToAnalyze = ragContext.substring(0, Math.min(2000, ragContext.length()));
        } else if (toolResult != null && !toolResult.isEmpty()) {
            contextToAnalyze = toolResult;
        }
        
        if (contextToAnalyze.isEmpty()) {
            return "";
        }
        
        String prompt = String.format("""
            À partir de la question et du contexte suivants, extrais les 3-5 points clés les plus importants.
            Réponds UNIQUEMENT par une liste à puces des points clés.
            
            QUESTION:
            %s
            
            CONTEXTE:
            %s
            
            POINTS CLÉS:
            """, question, contextToAnalyze);
        
        try {
            Response<dev.langchain4j.data.message.AiMessage> response = chatLanguageModel.generate(
                SystemMessage.from("Tu es un expert en extraction d'informations. Réponds UNIQUEMENT par une liste à puces des points clés."),
                UserMessage.from(prompt)
            );
            
            return response.content().text().trim();
            
        } catch (Exception e) {
            log.warn("Erreur lors de l'extraction de points clés: {}", e.getMessage());
            return "";
        }
    }
    
    /**
     * Suggère un template de réponse basé sur l'intention
     */
    private String suggestResponseTemplate(String intent, String keyPoints) {
        String prompt = String.format("""
            Basé sur l'intention suivante, suggère un template de réponse (structure, pas le contenu).
            
            INTENTION:
            %s
            
            POINTS CLÉS:
            %s
            
            TEMPLATE DE RÉPONSE (structure seulement):
            """, intent, keyPoints);
        
        try {
            Response<dev.langchain4j.data.message.AiMessage> response = chatLanguageModel.generate(
                SystemMessage.from("Tu es un expert en structuration de réponses. Suggère UNIQUEMENT la structure (template), pas le contenu."),
                UserMessage.from(prompt)
            );
            
            return response.content().text().trim();
            
        } catch (Exception e) {
            log.warn("Erreur lors de la suggestion de template: {}", e.getMessage());
            return "Répondre de manière claire et structurée";
        }
    }
}
