package net.youssfi.transactionservice.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Classe utilitaire pour classifier les questions et déterminer
 * si elles concernent les documents (RAG) ou les transactions (DB)
 */
@Component
@Slf4j
public class QuestionClassifier {
    
    // Mots-clés indiquant une question sur les documents/PDF/contenu
    private static final List<String> DOCUMENT_KEYWORDS = Arrays.asList(
        "document", "pdf", "fichier", "file",
        "analyse", "analysis", "analyser", "analyze",
        "données", "data", "dataset",
        "conclusion", "conclusions",
        "méthode", "method", "méthodes", "methods",
        "résultat", "result", "résultats", "results",
        "cours", "course", "formation", "training",
        "contenu", "content", "contenus",
        "résume", "summarize", "summary", "résumé", "résumés",
        "décris", "describe", "description",
        "explique", "explain", "explication",
        "qu'est-ce que", "what is", "what are",
        "définition", "definition",
        "image", "images", "photo", "photos",
        "rapport", "report", "rapports", "reports"
    );
    
    // Mots-clés indiquant une question sur les transactions
    private static final List<String> TRANSACTION_KEYWORDS = Arrays.asList(
        "transaction", "transactions",
        "compte", "account", "comptes", "accounts",
        "solde", "balance", "soldes", "balances",
        "montant", "amount", "montants", "amounts",
        "débit", "debit", "crédit", "credit",
        "statut", "status", "statuts", "statuses",
        "pending", "executed", "canceled", "cancelled",
        "en attente", "exécuté", "annulé", "annulée",
        "créer", "create", "ajouter", "add",
        "supprimer", "delete", "remove",
        "mettre à jour", "update", "modifier", "modify",
        "liste", "list", "afficher", "show", "display"
    );
    
    /**
     * Détermine si une question concerne les documents (RAG) ou les transactions (DB)
     * Classification stricte pour éviter toute confusion
     * @param question La question de l'utilisateur
     * @return QuestionType.DOCUMENT si c'est une question sur documents, QuestionType.TRANSACTION sinon
     */
    public QuestionType classify(String question) {
        if (question == null || question.trim().isEmpty()) {
            return QuestionType.TRANSACTION; // Par défaut, on assume une question sur transactions
        }
        
        String lowerQuestion = question.toLowerCase().trim();
        
        // Compter les occurrences de mots-clés
        int documentScore = countKeywords(lowerQuestion, DOCUMENT_KEYWORDS);
        int transactionScore = countKeywords(lowerQuestion, TRANSACTION_KEYWORDS);
        
        log.debug("Classification: documentScore={}, transactionScore={} pour '{}'", 
                documentScore, transactionScore, question);
        
        // RÈGLE 1: Si la question contient des mots-clés de documents, c'est une question sur documents
        if (documentScore > 0) {
            // Si elle contient aussi des mots-clés de transactions, on vérifie le contexte
            if (transactionScore > 0) {
                // Si les mots-clés de documents sont plus nombreux ou égaux, priorité documents
                if (documentScore >= transactionScore) {
                    log.info("✅ Question classifiée comme DOCUMENT (documentScore={} >= transactionScore={})", 
                            documentScore, transactionScore);
                    return QuestionType.DOCUMENT;
                } else {
                    // Si les mots-clés de transactions sont plus nombreux, priorité transactions
                    log.info("✅ Question classifiée comme TRANSACTION (transactionScore={} > documentScore={})", 
                            transactionScore, documentScore);
                    return QuestionType.TRANSACTION;
                }
            } else {
                // Pas de mots-clés de transactions, c'est clairement une question sur documents
                log.info("✅ Question classifiée comme DOCUMENT (documentScore={}, transactionScore=0)", documentScore);
                return QuestionType.DOCUMENT;
            }
        }
        
        // RÈGLE 2: Si on détecte des mots-clés de transactions, c'est une question sur transactions
        if (transactionScore > 0) {
            log.info("✅ Question classifiée comme TRANSACTION (transactionScore={}, documentScore=0)", transactionScore);
            return QuestionType.TRANSACTION;
        }
        
        // RÈGLE 3: Par défaut (aucun mot-clé détecté), on assume une question sur transactions
        log.info("✅ Question classifiée comme TRANSACTION (par défaut, aucun mot-clé spécifique)");
        return QuestionType.TRANSACTION;
    }
    
    /**
     * Compte le nombre de mots-clés présents dans la question
     */
    private int countKeywords(String question, List<String> keywords) {
        int count = 0;
        for (String keyword : keywords) {
            if (question.contains(keyword)) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Vérifie si une question nécessite l'utilisation du RAG
     */
    public boolean requiresRAG(String question) {
        return classify(question) == QuestionType.DOCUMENT;
    }
    
    /**
     * Vérifie si une question nécessite l'utilisation des outils de base de données
     */
    public boolean requiresDatabaseTools(String question) {
        return classify(question) == QuestionType.TRANSACTION;
    }
}
