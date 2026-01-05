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
 * Agent ReAct (Reasoning + Acting) qui implémente le pattern Think-Act-Observe
 * Fait partie de l'architecture Agentic RAG 2.0
 */
@Component
@Slf4j
public class ReActAgent {
    
    private final ChatLanguageModel chatLanguageModel;
    private final ReasoningAgent reasoningAgent;
    
    public ReActAgent(ChatLanguageModel chatLanguageModel, ReasoningAgent reasoningAgent) {
        this.chatLanguageModel = chatLanguageModel;
        this.reasoningAgent = reasoningAgent;
    }
    
    /**
     * Étape de raisonnement (Think)
     */
    public static class Thought {
        private final String reasoning; // Raisonnement
        private final String action; // Action suggérée
        private final String reasoningStep; // Étape de raisonnement
        
        public Thought(String reasoning, String action, String reasoningStep) {
            this.reasoning = reasoning;
            this.action = action;
            this.reasoningStep = reasoningStep;
        }
        
        public String getReasoning() { return reasoning; }
        public String getAction() { return action; }
        public String getReasoningStep() { return reasoningStep; }
    }
    
    /**
     * Observation après action (Observe)
     */
    public static class Observation {
        private final String result; // Résultat de l'action
        private final boolean success; // Succès ou échec
        private final String nextStep; // Prochaine étape suggérée
        
        public Observation(String result, boolean success, String nextStep) {
            this.result = result;
            this.success = success;
            this.nextStep = nextStep;
        }
        
        public String getResult() { return result; }
        public boolean isSuccess() { return success; }
        public String getNextStep() { return nextStep; }
    }
    
    /**
     * Exécute le cycle ReAct (Reasoning + Acting)
     * 
     * @param question La question de l'utilisateur
     * @param context Le contexte disponible (RAG ou DB)
     * @param maxIterations Nombre maximum d'itérations
     * @return Réponse finale
     */
    public String react(String question, String context, int maxIterations) {
        log.info("🔄 ReActAgent: Début du cycle ReAct");
        log.debug("   Question: {}", question);
        log.debug("   Max iterations: {}", maxIterations);
        
        List<String> thoughtHistory = new ArrayList<>();
        String currentContext = context;
        String finalAnswer = null;
        
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            log.info("   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("   🔄 Itération {}/{}", iteration, maxIterations);
            
            // 1. THINK: Raisonner sur la question et le contexte
            Thought thought = think(question, currentContext, thoughtHistory);
            thoughtHistory.add(String.format("Étape %d: %s", iteration, thought.getReasoning()));
            
            log.info("   💭 THINK: {}", thought.getReasoning());
            log.info("   🎯 Action suggérée: {}", thought.getAction());
            
            // 2. ACT: Décider si on a besoin d'une action ou si on peut répondre
            if ("ANSWER".equalsIgnoreCase(thought.getAction())) {
                // On peut répondre directement
                finalAnswer = generateAnswer(question, currentContext, thoughtHistory);
                log.info("   ✅ Réponse générée");
                break;
            } else if ("SEARCH_MORE".equalsIgnoreCase(thought.getAction())) {
                // Besoin de plus de contexte (déjà géré par le système RAG)
                log.info("   🔍 Action: Rechercher plus de contexte");
                // Le contexte devrait déjà être optimal, on continue
            } else if ("CLARIFY".equalsIgnoreCase(thought.getAction())) {
                // Besoin de clarification
                log.info("   ❓ Action: Demander clarification");
                finalAnswer = "Pourriez-vous préciser votre question ? " + thought.getReasoning();
                break;
            }
            
            // 3. OBSERVE: Observer le résultat (dans ce cas, on continue avec le contexte actuel)
            Observation observation = observe(currentContext, thought);
            log.info("   👁️ OBSERVE: {}", observation.getResult());
            
            if (observation.isSuccess() && "CONTINUE".equals(observation.getNextStep())) {
                // Continuer avec le contexte actuel
                currentContext = observation.getResult();
            } else if ("ANSWER".equals(observation.getNextStep())) {
                // On peut répondre maintenant
                finalAnswer = generateAnswer(question, currentContext, thoughtHistory);
                break;
            }
            
            // Éviter les boucles infinies
            if (iteration >= maxIterations) {
                log.warn("   ⚠️ Nombre maximum d'itérations atteint");
                finalAnswer = generateAnswer(question, currentContext, thoughtHistory);
                break;
            }
        }
        
        log.info("✅ ReActAgent: Cycle terminé");
        return finalAnswer != null ? finalAnswer : generateAnswer(question, currentContext, thoughtHistory);
    }
    
    /**
     * Étape THINK: Raisonner sur la question et le contexte
     */
    private Thought think(String question, String context, List<String> thoughtHistory) {
        String historyStr = thoughtHistory.isEmpty() ? "Aucune étape précédente" 
            : String.join("\n", thoughtHistory);
        
        String prompt = String.format("""
            Tu es un agent de raisonnement. Analyse la question et le contexte, puis décide de la prochaine action.
            
            QUESTION:
            %s
            
            CONTEXTE DISPONIBLE:
            %s
            
            HISTORIQUE DES ÉTAPES:
            %s
            
            Réponds au format suivant:
            RAISONNEMENT: [ton raisonnement en 2-3 phrases]
            ACTION: [ANSWER, SEARCH_MORE, ou CLARIFY]
            ÉTAPE: [description de l'étape de raisonnement]
            """, question, 
            context != null ? context.substring(0, Math.min(1500, context.length())) : "Aucun contexte",
            historyStr);
        
        try {
            Response<dev.langchain4j.data.message.AiMessage> response = chatLanguageModel.generate(
                SystemMessage.from("Tu es un agent de raisonnement. Analyse et décide de la prochaine action."),
                UserMessage.from(prompt)
            );
            
            String responseText = response.content().text();
            
            // Parser la réponse
            String reasoning = extractField(responseText, "RAISONNEMENT");
            String action = extractField(responseText, "ACTION");
            String step = extractField(responseText, "ÉTAPE");
            
            if (action == null || action.isEmpty()) {
                action = "ANSWER"; // Par défaut
            }
            
            return new Thought(reasoning, action, step);
            
        } catch (Exception e) {
            log.error("Erreur lors du raisonnement: {}", e.getMessage(), e);
            return new Thought("Erreur de raisonnement", "ANSWER", "Continuer avec la réponse");
        }
    }
    
    /**
     * Étape OBSERVE: Observer le résultat de l'action
     */
    private Observation observe(String context, Thought thought) {
        String prompt = String.format("""
            Analyse le contexte disponible après l'action suivante.
            
            ACTION EFFECTUÉE:
            %s
            
            CONTEXTE DISPONIBLE:
            %s
            
            Réponds au format:
            RÉSULTAT: [description du résultat]
            SUCCÈS: [OUI ou NON]
            PROCHAINE_ÉTAPE: [ANSWER, CONTINUE, ou SEARCH_MORE]
            """, thought.getAction(), 
            context != null ? context.substring(0, Math.min(1000, context.length())) : "Aucun contexte");
        
        try {
            Response<dev.langchain4j.data.message.AiMessage> response = chatLanguageModel.generate(
                SystemMessage.from("Tu es un agent d'observation. Analyse le résultat de l'action."),
                UserMessage.from(prompt)
            );
            
            String responseText = response.content().text();
            
            String result = extractField(responseText, "RÉSULTAT");
            String successStr = extractField(responseText, "SUCCÈS");
            String nextStep = extractField(responseText, "PROCHAINE_ÉTAPE");
            
            boolean success = successStr != null && successStr.toUpperCase().contains("OUI");
            if (nextStep == null || nextStep.isEmpty()) {
                nextStep = "ANSWER";
            }
            
            return new Observation(result, success, nextStep);
            
        } catch (Exception e) {
            log.error("Erreur lors de l'observation: {}", e.getMessage(), e);
            return new Observation("Erreur d'observation", false, "ANSWER");
        }
    }
    
    /**
     * Génère la réponse finale
     */
    private String generateAnswer(String question, String context, List<String> thoughtHistory) {
        String historyStr = thoughtHistory.isEmpty() ? "" 
            : "\n\nHistorique du raisonnement:\n" + String.join("\n", thoughtHistory);
        
        String prompt = String.format("""
            Réponds à la question suivante en utilisant le contexte fourni.
            
            QUESTION:
            %s
            
            CONTEXTE:
            %s
            %s
            
            RÉPONSE:
            """, question, 
            context != null ? context : "Aucun contexte disponible",
            historyStr);
        
        try {
            Response<dev.langchain4j.data.message.AiMessage> response = chatLanguageModel.generate(
                SystemMessage.from("Tu es un assistant expert. Réponds de manière claire et précise. Réponds TOUJOURS en FRANÇAIS."),
                UserMessage.from(prompt)
            );
            
            return response.content().text();
            
        } catch (Exception e) {
            log.error("Erreur lors de la génération de réponse: {}", e.getMessage(), e);
            return "Je n'ai pas pu générer de réponse. Veuillez réessayer.";
        }
    }
    
    /**
     * Extrait un champ d'une réponse structurée
     */
    private String extractField(String text, String fieldName) {
        String pattern = fieldName + ":";
        int startIdx = text.indexOf(pattern);
        if (startIdx == -1) return null;
        
        startIdx += pattern.length();
        int endIdx = text.indexOf("\n", startIdx);
        if (endIdx == -1) endIdx = text.length();
        
        return text.substring(startIdx, endIdx).trim();
    }
}
