package net.youssfi.transactionservice.agents;

import lombok.extern.slf4j.Slf4j;
import net.youssfi.transactionservice.service.TransactionToolService;
import org.springframework.stereotype.Component;

/**
 * Agent d'utilisation d'outils qui exécute des actions sur la base de données
 * Fait partie de l'architecture Agentic RAG 2.0
 */
@Component
@Slf4j
public class ToolUseAgent {
    
    private final TransactionToolService transactionToolService;
    
    public ToolUseAgent(TransactionToolService transactionToolService) {
        this.transactionToolService = transactionToolService;
    }
    
    /**
     * Exécute les outils appropriés selon la question
     * 
     * @param question La question de l'utilisateur
     * @return Résultat formaté des outils
     */
    public String execute(String question) {
        log.info("🛠️ ToolUseAgent: Exécution des outils pour '{}'", question);
        
        String result = transactionToolService.executeTools(question);
        
        if (result == null || result.isEmpty()) {
            log.warn("⚠️ Aucun résultat des outils");
            return "";
        }
        
        log.info("✅ Résultat des outils récupéré ({} caractères)", result.length());
        return result;
    }
}
