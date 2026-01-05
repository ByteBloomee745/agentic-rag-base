package net.youssfi.transactionservice.agents;

import lombok.extern.slf4j.Slf4j;
import net.youssfi.transactionservice.util.QuestionClassifier;
import net.youssfi.transactionservice.util.QuestionClassifier.QuestionType;
import org.springframework.stereotype.Component;

/**
 * Orchestrateur Multi-Agents qui coordonne tous les agents
 * Implémente l'architecture Agentic RAG 2.0 complète
 */
@Component
@Slf4j
public class MultiAgentOrchestrator {
    
    private final QuestionClassifier questionClassifier;
    private final RetrievalAgent retrievalAgent;
    private final ReasoningAgent reasoningAgent;
    private final VerificationAgent verificationAgent;
    private final ToolUseAgent toolUseAgent;
    private final ReActAgent reActAgent;
    
    public MultiAgentOrchestrator(
            QuestionClassifier questionClassifier,
            RetrievalAgent retrievalAgent,
            ReasoningAgent reasoningAgent,
            VerificationAgent verificationAgent,
            ToolUseAgent toolUseAgent,
            ReActAgent reActAgent) {
        this.questionClassifier = questionClassifier;
        this.retrievalAgent = retrievalAgent;
        this.reasoningAgent = reasoningAgent;
        this.verificationAgent = verificationAgent;
        this.toolUseAgent = toolUseAgent;
        this.reActAgent = reActAgent;
    }
    
    /**
     * Résultat de l'orchestration
     */
    public static class OrchestrationResult {
        private final String finalResponse;
        private final double confidenceScore;
        private final boolean wasCorrected;
        private final String reasoningIntent;
        
        public OrchestrationResult(String finalResponse, double confidenceScore, 
                                   boolean wasCorrected, String reasoningIntent) {
            this.finalResponse = finalResponse;
            this.confidenceScore = confidenceScore;
            this.wasCorrected = wasCorrected;
            this.reasoningIntent = reasoningIntent;
        }
        
        public String getFinalResponse() { return finalResponse; }
        public double getConfidenceScore() { return confidenceScore; }
        public boolean wasCorrected() { return wasCorrected; }
        public String getReasoningIntent() { return reasoningIntent; }
    }
    
    /**
     * Orchestre le traitement complet d'une question avec tous les agents
     * 
     * Pipeline: Classification → Retrieval → Reasoning → ReAct → Tool-Use → Verification → Réponse
     */
    public OrchestrationResult orchestrate(String question) {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("🎯 MultiAgentOrchestrator: Début de l'orchestration");
        log.info("   Question: {}", question);
        log.info("═══════════════════════════════════════════════════════════");
        
        try {
            // 1. CLASSIFICATION: Déterminer le type de question
            QuestionType questionType = questionClassifier.classify(question);
            log.info("📋 Classification: {}", questionType);
            
            String ragContext = "";
            String toolResult = null;
            
            // 2. RETRIEVAL AGENT: Chercher dans les documents (si question DOCUMENT)
            if (questionType == QuestionType.DOCUMENT) {
                log.info("🔍 Étape 1: Retrieval Agent");
                ragContext = retrievalAgent.search(question);
                log.info("   ✅ Contexte RAG récupéré ({} caractères)", 
                        ragContext != null ? ragContext.length() : 0);
            }
            
            // 3. TOOL-USE AGENT: Exécuter des actions (si question TRANSACTION)
            if (questionType == QuestionType.TRANSACTION) {
                log.info("🛠️ Étape 1: Tool-Use Agent");
                toolResult = toolUseAgent.execute(question);
                log.info("   ✅ Résultat des outils récupéré");
            }
            
            // 4. REASONING AGENT: Interpréter et structurer
            log.info("🧠 Étape 2: Reasoning Agent");
            ReasoningAgent.StructuredContext structured = reasoningAgent.interpretAndStructure(
                question, ragContext, toolResult
            );
            log.info("   ✅ Contexte structuré");
            log.debug("   Intention: {}", structured.getIntent());
            
            // 5. REACT AGENT: Raisonner et agir (optionnel, peut être désactivé)
            String response;
            boolean useReAct = true; // Peut être configuré
            
            if (useReAct) {
                log.info("🔄 Étape 3: ReAct Agent");
                response = reActAgent.react(
                    question, 
                    structured.getStructuredContext(), 
                    3 // Max 3 itérations
                );
                log.info("   ✅ Réponse générée via ReAct");
            } else {
                // Génération directe sans ReAct (fallback)
                log.info("⚡ Étape 3: Génération directe (sans ReAct)");
                response = generateDirectResponse(question, structured);
                log.info("   ✅ Réponse générée directement");
            }
            
            // 6. VERIFICATION AGENT: Vérifier et corriger
            log.info("🔍 Étape 4: Verification Agent");
            VerificationAgent.VerificationResult verification = verificationAgent.verify(
                question,
                response,
                structured.getStructuredContext()
            );
            log.info("   ✅ Vérification terminée (score: {:.2f})", verification.getConfidenceScore());
            
            String finalResponse = response;
            boolean wasCorrected = false;
            
            if (verification.needsCorrection() && verification.getCorrectedResponse() != null) {
                log.info("🔧 Correction appliquée");
                finalResponse = verification.getCorrectedResponse();
                wasCorrected = true;
            }
            
            log.info("═══════════════════════════════════════════════════════════");
            log.info("✅ Orchestration terminée");
            log.info("   Score de confiance: {:.2f}", verification.getConfidenceScore());
            log.info("   Correction appliquée: {}", wasCorrected);
            log.info("═══════════════════════════════════════════════════════════");
            
            return new OrchestrationResult(
                finalResponse,
                verification.getConfidenceScore(),
                wasCorrected,
                structured.getIntent()
            );
            
        } catch (Exception e) {
            log.error("❌ Erreur lors de l'orchestration: {}", e.getMessage(), e);
            return new OrchestrationResult(
                "Erreur lors du traitement de votre question. Veuillez réessayer.",
                0.0,
                false,
                "error"
            );
        }
    }
    
    /**
     * Génère une réponse directe sans ReAct (fallback)
     */
    private String generateDirectResponse(String question, ReasoningAgent.StructuredContext structured) {
        // Cette méthode serait normalement appelée par le LLM
        // Pour l'instant, on retourne une réponse basique
        return "Réponse générée à partir du contexte structuré pour: " + question;
    }
}
