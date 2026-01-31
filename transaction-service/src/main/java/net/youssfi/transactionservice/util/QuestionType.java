package net.youssfi.transactionservice.util;

/**
 * Type de question pour la classification
 */
public enum QuestionType {
    DOCUMENT,    // Question sur les documents/PDF/contenu -> utilise RAG
    TRANSACTION  // Question sur les transactions -> utilise DB tools
}
