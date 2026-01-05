package net.youssfi.transactionservice.agents;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent de vérification qui valide et corrige les réponses générées
 * Fait partie de l'architecture Agentic RAG 2.0
 */
@Component
@Slf4j
public class VerificationAgent {
    
    private final ChatLanguageModel chatLanguageModel;
    
    public VerificationAgent(ChatLanguageModel chatLanguageModel) {
        this.chatLanguageModel = chatLanguageModel;
    }
    
    /**
     * Résultat de la vérification
     */
    public static class VerificationResult {
        private final double confidenceScore; // 0.0 à 1.0
        private final boolean needsCorrection;
        private final String issues; // Problèmes détectés
        private final String correctedResponse; // Réponse corrigée si nécessaire
        
        public VerificationResult(double confidenceScore, boolean needsCorrection, 
                                 String issues, String correctedResponse) {
            this.confidenceScore = confidenceScore;
            this.needsCorrection = needsCorrection;
            this.issues = issues;
            this.correctedResponse = correctedResponse;
        }
        
        public double getConfidenceScore() { return confidenceScore; }
        public boolean needsCorrection() { return needsCorrection; }
        public String getIssues() { return issues; }
        public String getCorrectedResponse() { return correctedResponse; }
    }
    
    /**
     * Vérifie la qualité et la cohérence d'une réponse générée
     * 
     * @param originalQuestion La question originale de l'utilisateur
     * @param generatedResponse La réponse générée par le LLM
     * @param context Le contexte utilisé (RAG ou DB)
     * @return Résultat de la vérification
     */
    public VerificationResult verify(String originalQuestion, 
                                     String generatedResponse, 
                                     String context) {
        log.info("🔍 VerificationAgent: Début de la vérification");
        log.debug("   Question: {}", originalQuestion);
        log.debug("   Réponse: {}...", generatedResponse.substring(0, Math.min(100, generatedResponse.length())));
        
        try {
            // 1. Vérifier la cohérence avec le contexte
            double coherenceScore = checkCoherence(generatedResponse, context);
            
            // 2. Détecter les hallucinations (réponses sans base dans le contexte)
            double hallucinationScore = detectHallucinations(generatedResponse, context);
            
            // 3. Vérifier la pertinence par rapport à la question
            double relevanceScore = checkRelevance(originalQuestion, generatedResponse);
            
            // 4. Calculer le score de confiance global
            double confidenceScore = (coherenceScore * 0.4 + 
                                    hallucinationScore * 0.4 + 
                                    relevanceScore * 0.2);
            
            // 5. Détecter les problèmes
            List<String> issues = new ArrayList<>();
            if (coherenceScore < 0.6) {
                issues.add("Faible cohérence avec le contexte");
            }
            if (hallucinationScore < 0.7) {
                issues.add("Possible hallucination détectée");
            }
            if (relevanceScore < 0.6) {
                issues.add("Réponse peu pertinente par rapport à la question");
            }
            
            boolean needsCorrection = confidenceScore < 0.7 || !issues.isEmpty();
            
            String correctedResponse = null;
            if (needsCorrection) {
                correctedResponse = correctResponse(originalQuestion, generatedResponse, context, issues);
            }
            
            log.info("✅ VerificationAgent: Score de confiance = {:.2f}, Correction nécessaire = {}", 
                    confidenceScore, needsCorrection);
            
            return new VerificationResult(
                confidenceScore,
                needsCorrection,
                String.join("; ", issues),
                correctedResponse
            );
            
        } catch (Exception e) {
            log.error("❌ Erreur lors de la vérification: {}", e.getMessage(), e);
            // En cas d'erreur, on accepte la réponse originale
            return new VerificationResult(0.5, false, "Erreur de vérification", null);
        }
    }
    
    /**
     * Vérifie la cohérence de la réponse avec le contexte
     */
    private double checkCoherence(String response, String context) {
        if (context == null || context.isEmpty()) {
            return 0.5; // Score neutre si pas de contexte
        }
        
        String prompt = String.format("""
            Analyse la cohérence entre la réponse suivante et le contexte fourni.
            Réponds UNIQUEMENT par un score entre 0.0 et 1.0 (0.0 = pas cohérent, 1.0 = très cohérent).
            
            CONTEXTE:
            %s
            
            RÉPONSE:
            %s
            
            Score de cohérence (0.0-1.0):
            """, context.substring(0, Math.min(2000, context.length())), 
            response.substring(0, Math.min(1000, response.length())));
        
        try {
            String scoreStr = chatLanguageModel.generate(
                SystemMessage.from("Tu es un expert en analyse de cohérence. Réponds UNIQUEMENT par un nombre entre 0.0 et 1.0."),
                UserMessage.from(prompt)
            ).content().text();
            
            // Extraire le score numérique
            scoreStr = scoreStr.trim().replaceAll("[^0-9.]", "");
            if (scoreStr.isEmpty()) return 0.5;
            
            double score = Double.parseDouble(scoreStr);
            return Math.max(0.0, Math.min(1.0, score)); // Clamp entre 0 et 1
            
        } catch (Exception e) {
            log.warn("Erreur lors du calcul de cohérence: {}", e.getMessage());
            return 0.5;
        }
    }
    
    /**
     * Détecte les hallucinations (informations inventées)
     */
    private double detectHallucinations(String response, String context) {
        if (context == null || context.isEmpty()) {
            return 0.3; // Faible score si pas de contexte pour vérifier
        }
        
        String prompt = String.format("""
            Analyse si la réponse suivante contient des informations qui ne sont PAS dans le contexte.
            Réponds UNIQUEMENT par un score entre 0.0 et 1.0 (0.0 = beaucoup d'hallucinations, 1.0 = aucune hallucination).
            
            CONTEXTE:
            %s
            
            RÉPONSE:
            %s
            
            Score (0.0-1.0):
            """, context.substring(0, Math.min(2000, context.length())), 
            response.substring(0, Math.min(1000, response.length())));
        
        try {
            String scoreStr = chatLanguageModel.generate(
                SystemMessage.from("Tu es un expert en détection d'hallucinations. Réponds UNIQUEMENT par un nombre entre 0.0 et 1.0."),
                UserMessage.from(prompt)
            ).content().text();
            
            scoreStr = scoreStr.trim().replaceAll("[^0-9.]", "");
            if (scoreStr.isEmpty()) return 0.5;
            
            double score = Double.parseDouble(scoreStr);
            return Math.max(0.0, Math.min(1.0, score));
            
        } catch (Exception e) {
            log.warn("Erreur lors de la détection d'hallucinations: {}", e.getMessage());
            return 0.5;
        }
    }
    
    /**
     * Vérifie la pertinence de la réponse par rapport à la question
     */
    private double checkRelevance(String question, String response) {
        String prompt = String.format("""
            Analyse si la réponse suivante répond bien à la question posée.
            Réponds UNIQUEMENT par un score entre 0.0 et 1.0 (0.0 = pas pertinent, 1.0 = très pertinent).
            
            QUESTION:
            %s
            
            RÉPONSE:
            %s
            
            Score de pertinence (0.0-1.0):
            """, question, response.substring(0, Math.min(1000, response.length())));
        
        try {
            String scoreStr = chatLanguageModel.generate(
                SystemMessage.from("Tu es un expert en analyse de pertinence. Réponds UNIQUEMENT par un nombre entre 0.0 et 1.0."),
                UserMessage.from(prompt)
            ).content().text();
            
            scoreStr = scoreStr.trim().replaceAll("[^0-9.]", "");
            if (scoreStr.isEmpty()) return 0.5;
            
            double score = Double.parseDouble(scoreStr);
            return Math.max(0.0, Math.min(1.0, score));
            
        } catch (Exception e) {
            log.warn("Erreur lors du calcul de pertinence: {}", e.getMessage());
            return 0.5;
        }
    }
    
    /**
     * Corrige la réponse en fonction des problèmes détectés
     */
    private String correctResponse(String question, String originalResponse, 
                                  String context, List<String> issues) {
        log.info("🔧 VerificationAgent: Correction de la réponse");
        
        String issuesStr = String.join(", ", issues);
        
        String prompt = String.format("""
            La réponse suivante a été générée mais présente des problèmes: %s
            
            QUESTION ORIGINALE:
            %s
            
            CONTEXTE DISPONIBLE:
            %s
            
            RÉPONSE ORIGINALE (à corriger):
            %s
            
            PROBLÈMES DÉTECTÉS:
            %s
            
            Génère une réponse CORRIGÉE qui:
            1. Répond mieux à la question
            2. Utilise uniquement les informations du contexte
            3. Évite les hallucinations
            4. Est cohérente avec le contexte
            
            RÉPONSE CORRIGÉE:
            """, issuesStr, question, 
            context != null ? context.substring(0, Math.min(2000, context.length())) : "Aucun contexte",
            originalResponse.substring(0, Math.min(1000, originalResponse.length())),
            issuesStr);
        
        try {
            Response<dev.langchain4j.data.message.AiMessage> response = chatLanguageModel.generate(
                SystemMessage.from("Tu es un expert en correction de réponses. Génère une réponse améliorée basée sur le contexte. Réponds TOUJOURS en FRANÇAIS."),
                UserMessage.from(prompt)
            );
            
            String corrected = response.content().text();
            log.info("✅ Réponse corrigée générée ({} caractères)", corrected.length());
            return corrected;
            
        } catch (Exception e) {
            log.error("❌ Erreur lors de la correction: {}", e.getMessage(), e);
            return originalResponse; // Retourner l'originale en cas d'erreur
        }
    }
}
