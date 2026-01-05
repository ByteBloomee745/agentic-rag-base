package net.youssfi.transactionservice.web;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import net.youssfi.transactionservice.agents.TransactionAIAgent;
import net.youssfi.transactionservice.agents.TransactionAiTools;
import net.youssfi.transactionservice.agents.MultiAgentOrchestrator;
import net.youssfi.transactionservice.service.TransactionToolService;
import net.youssfi.transactionservice.util.QuestionClassifier;
import net.youssfi.transactionservice.util.QuestionClassifier.QuestionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;

@RestController
@CrossOrigin("*")
@Slf4j
public class AIAssistantController {
    private final StreamingChatLanguageModel streamingChatLanguageModel;
    private final ChatMemoryProvider chatMemoryProvider;
    private final TransactionToolService transactionToolService;
    private final TransactionAiTools transactionAiTools;
    private final QuestionClassifier questionClassifier;
    
    @Autowired(required = false)
    private MultiAgentOrchestrator multiAgentOrchestrator; // Orchestrateur multi-agents (optionnel)
    
    @Autowired(required = false)
    private EmbeddingStore<TextSegment> embeddingStore; // RAG Embedding Store
    
    @Autowired(required = false)
    private EmbeddingModel embeddingModel; // Embedding Model pour RAG
    
    @Value("${rag.retriever.max-results:30}")
    private int maxResults;
    
    @Value("${rag.retriever.min-score:0.0}")
    private double minScore;
    
    @Autowired(required = false)
    private TransactionAIAgent transactionAIAgent; // Peut être null si le modèle ne supporte pas les function calls
    
    public AIAssistantController(
            StreamingChatLanguageModel streamingChatLanguageModel,
            ChatMemoryProvider chatMemoryProvider,
            TransactionToolService transactionToolService,
            TransactionAiTools transactionAiTools,
            QuestionClassifier questionClassifier){
        this.streamingChatLanguageModel = streamingChatLanguageModel;
        this.chatMemoryProvider = chatMemoryProvider;
        this.transactionToolService = transactionToolService;
        this.transactionAiTools = transactionAiTools;
        this.questionClassifier = questionClassifier;
    }

    @GetMapping("/askAgent")
    public Flux<String> chat(
            @RequestParam(defaultValue = "Bonjour") String question,
            @RequestParam(required = false, defaultValue = "default") String chatId) {
        
        try {
            // Décoder la question si elle est encodée (gestion des caractères spéciaux dans l'URL)
            String decodedQuestion = java.net.URLDecoder.decode(question, java.nio.charset.StandardCharsets.UTF_8);
            if (!decodedQuestion.equals(question)) {
                log.debug("Question décodée: '{}' -> '{}'", question, decodedQuestion);
                question = decodedQuestion;
            }
            
            // Classifier la question
            QuestionType questionType = questionClassifier.classify(question);
            boolean isDocumentQuestion = questionType == QuestionType.DOCUMENT;
            boolean isTransactionQuestion = questionType == QuestionType.TRANSACTION;
            
            log.info("Question classifiée: {} - '{}'", questionType, question);
            
            // Récupérer la mémoire conversationnelle pour ce chatId
            MessageWindowChatMemory chatMemory = (MessageWindowChatMemory) chatMemoryProvider.get((Object) chatId);
            
            // Récupérer l'historique existant
            List<dev.langchain4j.data.message.ChatMessage> previousMessages = new ArrayList<>(chatMemory.messages());
            
            // SÉPARATION STRICTE: RAG pour documents, DB pour transactions
            String ragContext = "";
            String toolResult = null;
            
            if (isDocumentQuestion) {
                if (embeddingStore != null && embeddingModel != null) {
                    ragContext = retrieveRAGContext(question);
                    log.info("Mode DOCUMENTS: Contexte RAG {} récupéré", 
                            ragContext.isEmpty() ? "non" : "");
                }
            } else if (isTransactionQuestion) {
                toolResult = transactionToolService.executeTools(question);
                log.info("Mode TRANSACTIONS: Données DB {} récupérées", 
                        (toolResult != null && !toolResult.isEmpty()) ? "" : "non");
            }
            
            // Construire le message système selon le type de question
            String systemPrompt = buildSystemPrompt(questionType, ragContext, toolResult);
            SystemMessage systemMessage = SystemMessage.from(systemPrompt);
            
            // Construire le message utilisateur
            String userMessageText = buildUserMessage(question, ragContext, toolResult, isDocumentQuestion);
            UserMessage userMessage = UserMessage.from(userMessageText);
            
            // Construire la liste complète des messages
            List<dev.langchain4j.data.message.ChatMessage> allMessages = new ArrayList<>();
            allMessages.add(systemMessage);
            allMessages.addAll(previousMessages);
            allMessages.add(userMessage);
            
            // Générer la réponse
            return generateResponse(allMessages, chatMemory, userMessage);
            
        } catch (Exception e) {
            log.error("Erreur lors du traitement de la question: {}", e.getMessage(), e);
            return Flux.just("Erreur: " + e.getMessage());
        }
    }
    
    /**
     * Récupère le contexte RAG depuis le vector store
     */
    private String retrieveRAGContext(String question) {
        try {
            log.info("═══════════════════════════════════════════════════════════");
            log.info("🔍 RAG: Début de la recherche de contenu");
            log.info("   Question: '{}'", question);
            log.info("   maxResults: {}, minScore: {}", maxResults, minScore);
            
            // Vérifier que l'embeddingStore est disponible
            if (embeddingStore == null) {
                log.error("❌ embeddingStore est null!");
                return "";
            }
            
            // Vérifier que l'embeddingModel est disponible
            if (embeddingModel == null) {
                log.error("❌ embeddingModel est null!");
                return "";
            }
            
            // Générer l'embedding de la question
            log.info("   Génération de l'embedding de la question...");
            dev.langchain4j.data.embedding.Embedding queryEmbedding = embeddingModel.embed(question).content();
            log.info("   ✅ Embedding généré (dimension: {})", queryEmbedding.dimension());
            
            // Obtenir la méthode de recherche
            log.info("   Recherche de la méthode findRelevant...");
            java.lang.reflect.Method findRelevantMethod = embeddingStore.getClass()
                    .getMethod("findRelevant", 
                            dev.langchain4j.data.embedding.Embedding.class, 
                            int.class, 
                            double.class);
            log.info("   ✅ Méthode findRelevant trouvée");
            
            // Recherche progressive avec seuils décroissants
            log.info("   Début de la recherche dans le vector store...");
            List<?> relevantMatches = searchInVectorStore(findRelevantMethod, queryEmbedding, question);
            
            if (relevantMatches == null || relevantMatches.isEmpty()) {
                log.warn("⚠️ Aucun contenu trouvé dans le vector store pour: '{}'", question);
                log.warn("   Vérifiez que:");
                log.warn("   1. Les documents sont bien chargés dans le vector store");
                log.warn("   2. Le vector store PostgreSQL est accessible");
                log.warn("   3. Les embeddings ont été générés correctement");
                return "";
            }
            
            log.info("✅ RAG: {} résultats trouvés", relevantMatches.size());
            String ragContext = buildRAGContext(relevantMatches);
            log.info("✅ RAG: Contexte construit ({} caractères)", ragContext.length());
            log.info("═══════════════════════════════════════════════════════════");
            return ragContext;
            
        } catch (Exception e) {
            log.error("❌ Erreur lors de la récupération RAG: {}", e.getMessage(), e);
            log.error("   Stack trace:", e);
            return "";
        }
    }
    
    /**
     * Recherche dans le vector store avec seuils progressifs
     * Recherche très agressive pour trouver du contenu même avec faible similarité
     */
    private List<?> searchInVectorStore(java.lang.reflect.Method findRelevantMethod,
                                       dev.langchain4j.data.embedding.Embedding queryEmbedding,
                                       String question) throws Exception {
        // Commencer directement avec un seuil très bas pour être sûr de trouver quelque chose
        double[] scoreThresholds = {0.0, 0.1, 0.2, 0.3, 0.5};
        int searchMaxResults = Math.max(maxResults, 30); // Augmenter le nombre de résultats
        
        // Recherche principale avec seuils progressifs (commencer par 0.0)
        for (double threshold : scoreThresholds) {
            @SuppressWarnings("unchecked")
            List<?> matches = (List<?>) findRelevantMethod.invoke(
                    embeddingStore, queryEmbedding, searchMaxResults, threshold);
            
            if (matches != null && !matches.isEmpty()) {
                log.info("✅ {} résultats trouvés avec minScore={}", matches.size(), threshold);
                return matches;
            }
        }
        
        // Recherche très large si aucun résultat
        log.warn("⚠️ Aucun résultat avec seuils normaux, tentative recherche très large...");
        @SuppressWarnings("unchecked")
        List<?> allMatches = (List<?>) findRelevantMethod.invoke(
                embeddingStore, queryEmbedding, 100, 0.0); // Chercher jusqu'à 100 résultats
        
        if (allMatches != null && !allMatches.isEmpty()) {
            log.info("✅ {} résultats trouvés avec recherche très large (minScore=0.0, maxResults=100)", 
                    allMatches.size());
            return allMatches;
        }
        
        // Dernière tentative: recherche par mots-clés
        log.warn("⚠️ Aucun résultat avec recherche large, tentative par mots-clés...");
        List<?> keywordResults = searchByKeywords(findRelevantMethod, question);
        if (keywordResults != null && !keywordResults.isEmpty()) {
            return keywordResults;
        }
        
        // Dernière tentative absolue: récupérer TOUS les documents disponibles
        log.warn("⚠️ Aucun résultat avec recherche par mots-clés, tentative récupération de TOUS les documents...");
        return getAllDocumentsFromStore(findRelevantMethod, queryEmbedding);
    }
    
    /**
     * Récupère TOUS les documents du store (fallback ultime)
     */
    private List<?> getAllDocumentsFromStore(java.lang.reflect.Method findRelevantMethod,
                                             dev.langchain4j.data.embedding.Embedding queryEmbedding) {
        try {
            // Essayer plusieurs stratégies pour récupérer tous les documents
            
            // Stratégie 1: Score très négatif pour tout récupérer
            @SuppressWarnings("unchecked")
            List<?> allDocs = (List<?>) findRelevantMethod.invoke(
                    embeddingStore, queryEmbedding, 1000, -10.0);
            
            if (allDocs != null && !allDocs.isEmpty()) {
                log.info("✅ {} documents récupérés en mode fallback (score=-10.0)", allDocs.size());
                return allDocs;
            }
            
            // Stratégie 2: Embedding générique "document"
            try {
                dev.langchain4j.data.embedding.Embedding genericEmbedding = embeddingModel.embed("document").content();
                @SuppressWarnings("unchecked")
                List<?> genericResults = (List<?>) findRelevantMethod.invoke(
                        embeddingStore, genericEmbedding, 1000, -10.0);
                
                if (genericResults != null && !genericResults.isEmpty()) {
                    log.info("✅ {} documents récupérés avec embedding 'document'", genericResults.size());
                    return genericResults;
                }
            } catch (Exception e) {
                log.debug("Erreur avec embedding 'document': {}", e.getMessage());
            }
            
            // Stratégie 3: Embedding "texte" ou "contenu"
            String[] fallbackTerms = {"texte", "contenu", "information", "données", "analyse"};
            for (String term : fallbackTerms) {
                try {
                    dev.langchain4j.data.embedding.Embedding termEmbedding = embeddingModel.embed(term).content();
                    @SuppressWarnings("unchecked")
                    List<?> termResults = (List<?>) findRelevantMethod.invoke(
                            embeddingStore, termEmbedding, 1000, -10.0);
                    
                    if (termResults != null && !termResults.isEmpty()) {
                        log.info("✅ {} documents récupérés avec embedding '{}'", termResults.size(), term);
                        return termResults;
                    }
                } catch (Exception e) {
                    log.debug("Erreur avec embedding '{}': {}", term, e.getMessage());
                }
            }
            
            // Stratégie 4: Essayer avec un embedding vide ou minimal
            try {
                dev.langchain4j.data.embedding.Embedding emptyEmbedding = embeddingModel.embed("a").content();
                @SuppressWarnings("unchecked")
                List<?> emptyResults = (List<?>) findRelevantMethod.invoke(
                        embeddingStore, emptyEmbedding, 1000, -10.0);
                
                if (emptyResults != null && !emptyResults.isEmpty()) {
                    log.info("✅ {} documents récupérés avec embedding minimal", emptyResults.size());
                    return emptyResults;
                }
            } catch (Exception e) {
                log.debug("Erreur avec embedding minimal: {}", e.getMessage());
            }
            
        } catch (Exception e) {
            log.warn("Erreur lors de la récupération de tous les documents: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Recherche par mots-clés extraits de la question
     * Recherche très agressive avec plusieurs stratégies
     */
    private List<?> searchByKeywords(java.lang.reflect.Method findRelevantMethod, String question) {
        try {
            // Extraire les mots-clés importants
            String[] keywords = question.toLowerCase().split("\\s+");
            List<String> importantKeywords = new ArrayList<>();
            
            for (String keyword : keywords) {
                // Filtrer les mots trop courts et les mots vides
                if (keyword.length() > 3 && !keyword.matches("^(le|la|les|un|une|de|du|des|et|ou|est|sont|dans|pour|avec)$")) {
                    importantKeywords.add(keyword);
                }
            }
            
            // Essayer chaque mot-clé important
            for (String keyword : importantKeywords) {
                try {
                    dev.langchain4j.data.embedding.Embedding keywordEmbedding = 
                            embeddingModel.embed(keyword).content();
                    @SuppressWarnings("unchecked")
                    List<?> matches = (List<?>) findRelevantMethod.invoke(
                            embeddingStore, keywordEmbedding, 20, 0.0); // Augmenter à 20 résultats
                    
                    if (matches != null && !matches.isEmpty()) {
                        log.info("✅ {} résultats trouvés avec le mot-clé '{}'", matches.size(), keyword);
                        return matches;
                    }
                } catch (Exception e) {
                    log.debug("Erreur avec le mot-clé '{}': {}", keyword, e.getMessage());
                }
            }
            
            // Si aucun résultat, essayer des termes génériques liés à la question
            String[] genericTerms = {"document", "contenu", "texte", "information", "données", "analyse", 
                                     "analyse de données", "cours", "résumé", "introduction", "méthode", 
                                     "technique", "statistique", "apprentissage", "machine learning"};
            
            // Ajouter des termes spécifiques basés sur la question
            String questionLower = question.toLowerCase();
            if (questionLower.contains("analyse") || questionLower.contains("données")) {
                genericTerms = new String[]{"analyse de données", "analyse", "données", "statistique", 
                                           "méthode", "technique", "cours", "résumé", "introduction"};
            } else if (questionLower.contains("cours") || questionLower.contains("résumé")) {
                genericTerms = new String[]{"cours", "résumé", "introduction", "document", "contenu", 
                                           "texte", "information", "analyse"};
            }
            
            for (String term : genericTerms) {
                try {
                    dev.langchain4j.data.embedding.Embedding termEmbedding = 
                            embeddingModel.embed(term).content();
                    @SuppressWarnings("unchecked")
                    List<?> matches = (List<?>) findRelevantMethod.invoke(
                            embeddingStore, termEmbedding, 50, 0.0); // Augmenter à 50 résultats
                    
                    if (matches != null && !matches.isEmpty()) {
                        log.info("✅ {} résultats trouvés avec le terme générique '{}'", matches.size(), term);
                        return matches;
                    }
                } catch (Exception e) {
                    log.debug("Erreur avec le terme générique '{}': {}", term, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Erreur lors de la recherche par mots-clés: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Construit le contexte RAG formaté à partir des matches
     */
    private String buildRAGContext(List<?> relevantMatches) {
        StringBuilder ragBuilder = new StringBuilder();
        ragBuilder.append("═══════════════════════════════════════════════════════════\n");
        ragBuilder.append("📚 CONTEXTE PERTINENT DEPUIS LES DOCUMENTS CHARGÉS\n");
        ragBuilder.append("═══════════════════════════════════════════════════════════\n\n");
        ragBuilder.append("⚠️ INSTRUCTIONS CRITIQUES:\n");
        ragBuilder.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        ragBuilder.append("1. Les informations ci-dessous proviennent UNIQUEMENT des documents PDF/documents chargés.\n");
        ragBuilder.append("2. Vous DEVEZ répondre EXCLUSIVEMENT en utilisant ces informations de documents.\n");
        ragBuilder.append("3. INTERDICTION ABSOLUE: Ne JAMAIS mentionner:\n");
        ragBuilder.append("   - Les outils de base de données (getAllTransactions, calculateAccountBalance, etc.)\n");
        ragBuilder.append("   - Les transactions, comptes, soldes, ou toute information financière de la base de données\n");
        ragBuilder.append("   - Les opérations de base de données ou SQL\n");
        ragBuilder.append("4. Si l'information n'est pas dans les documents, dites-le clairement.\n");
        ragBuilder.append("5. Ne pas inventer d'informations.\n");
        ragBuilder.append("6. Citez directement le contenu des documents ci-dessous.\n\n");
        ragBuilder.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        ragBuilder.append("CONTENU DES DOCUMENTS:\n");
        ragBuilder.append("───────────────────────────────────────────────────────────\n\n");
        
        int segmentIndex = 1;
        int totalChars = 0;
        for (Object match : relevantMatches) {
            try {
                TextSegment segment = extractTextSegment(match);
                if (segment != null && segment.text() != null && !segment.text().trim().isEmpty()) {
                    String segmentText = segment.text().trim();
                    // Augmenter la limite pour avoir plus de contenu
                    if (segmentText.length() > 5000) {
                        segmentText = segmentText.substring(0, 5000) + "...";
                    }
                    
                    ragBuilder.append("【 Extrait ").append(segmentIndex).append(" 】\n");
                    ragBuilder.append(segmentText).append("\n\n");
                    totalChars += segmentText.length();
                    segmentIndex++;
                }
            } catch (Exception e) {
                log.debug("Erreur lors du traitement d'un match: {}", e.getMessage());
            }
        }
        
        ragBuilder.append("═══════════════════════════════════════════════════════════\n");
        log.info("✅ RAG: {} segments ajoutés au contexte ({} caractères)", segmentIndex - 1, totalChars);
        return ragBuilder.toString();
    }
    
    /**
     * Extrait un TextSegment d'un match (gère différentes versions de l'API)
     */
    private TextSegment extractTextSegment(Object match) {
        if (match instanceof TextSegment) {
            return (TextSegment) match;
        }
        
        // Essayer d'appeler embedded() ou getEmbedded() via réflexion
        try {
            java.lang.reflect.Method embeddedMethod = match.getClass().getMethod("embedded");
            return (TextSegment) embeddedMethod.invoke(match);
        } catch (Exception e) {
            try {
                java.lang.reflect.Method getEmbeddedMethod = match.getClass().getMethod("getEmbedded");
                return (TextSegment) getEmbeddedMethod.invoke(match);
            } catch (Exception e2) {
                log.debug("Impossible d'extraire le segment du match: {}", e2.getMessage());
                return null;
            }
        }
    }
    
    /**
     * Construit le message système selon le type de question
     */
    private String buildSystemPrompt(QuestionType questionType, String ragContext, String toolResult) {
        if (questionType == QuestionType.DOCUMENT) {
            String prompt = "Tu es un ASSISTANT D'ANALYSE DE DOCUMENTS. Ton SEUL objectif est de répondre aux questions sur les DOCUMENTS, PDFs et CONTENUS.\n\n" +
                   "🚫 INTERDICTIONS ABSOLUES:\n" +
                   "- Ne JAMAIS mentionner les outils de base de données (getAllTransactions, calculateAccountBalance, etc.)\n" +
                   "- Ne JAMAIS mentionner les transactions, comptes, soldes ou toute information financière de la base de données\n" +
                   "- Ne JAMAIS dire 'de la base de données' ou 'en utilisant les outils de base de données'\n" +
                   "- Ne JAMAIS parler d'opérations de base de données ou de requêtes SQL\n\n" +
                   "✅ INSTRUCTIONS CRITIQUES:\n" +
                   "- L'utilisateur a posé une question sur les DOCUMENTS ou CONTENUS des fichiers chargés.\n" +
                   "- Tu DOIS répondre EXCLUSIVEMENT en utilisant les informations fournies dans la section 'CONTEXTE PERTINENT DEPUIS LES DOCUMENTS' ci-dessous.\n" +
                   "- Lis attentivement le contenu des documents et cite directement.\n" +
                   "- Si le contexte du document contient la réponse, utilise-le directement.\n" +
                   "- Si aucun contexte de document n'est fourni ou si l'information n'est pas dans les documents, dis: 'Je suis désolé, mais cette information n'est pas disponible dans les documents fournis. Veuillez vous assurer que les documents sont chargés dans le système.'\n" +
                   "- Concentre-toi UNIQUEMENT sur le contenu des documents: analyses, méthodes, conclusions, techniques d'analyse de données, résultats de recherche, etc.\n" +
                   "- N'invente pas d'informations.\n" +
                   "- Cite des parties spécifiques des documents lors de la réponse.\n\n" +
                   "Rappel: Tu es un assistant de DOCUMENTS, PAS un assistant de base de données.\n\n" +
                   "IMPORTANT: Réponds TOUJOURS en FRANÇAIS.";
            
            if (ragContext.isEmpty()) {
                prompt += "\n\n⚠️ ATTENTION: Aucun contexte de document n'a été trouvé dans la section 'CONTEXTE PERTINENT DEPUIS LES DOCUMENTS'. " +
                         "Tu DOIS informer l'utilisateur que l'information n'est pas disponible dans les documents chargés. " +
                         "N'utilise PAS les outils de base de données et ne mentionne PAS les transactions.";
            } else {
                prompt += "\n\n✅ IMPORTANT: Le contexte du document EST FOURNI dans la section 'CONTEXTE PERTINENT DEPUIS LES DOCUMENTS'. " +
                         "Tu DOIS utiliser ce contexte pour répondre à la question de l'utilisateur. Lis-le attentivement et base ta réponse dessus.";
            }
            return prompt;
        } else {
            return "Tu es un ASSISTANT DE GESTION DE TRANSACTIONS. Réponds aux questions sur les TRANSACTIONS.\n\n" +
                   "INTERDICTIONS:\n" +
                   "- Ne JAMAIS mentionner les documents, PDFs ou le contenu des documents\n\n" +
                   "INSTRUCTIONS:\n" +
                   "- Utilise UNIQUEMENT les données de transaction dans la section 'Données récupérées de la base de données'\n" +
                   "- Outils disponibles: getAllTransactions, getAllTransactionsByAccountId, getTransactionsByStatus, " +
                   "getTransactionById, updateTransactionStatus, createTransaction, deleteTransaction, calculateAccountBalance\n\n" +
                   "Fournis des réponses précises basées sur les données de transaction.\n\n" +
                   "IMPORTANT: Réponds TOUJOURS en FRANÇAIS.";
        }
    }
    
    /**
     * Construit le message utilisateur avec le contexte approprié
     */
    private String buildUserMessage(String question, String ragContext, String toolResult, boolean isDocumentQuestion) {
        StringBuilder messageBuilder = new StringBuilder();
        
        if (isDocumentQuestion) {
            if (!ragContext.isEmpty()) {
                // Le contexte RAG est déjà formaté avec toutes les instructions
                messageBuilder.append(ragContext);
                messageBuilder.append("\n\n");
                messageBuilder.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                messageBuilder.append("❓ QUESTION DE L'UTILISATEUR:\n");
                messageBuilder.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                messageBuilder.append(question);
                messageBuilder.append("\n\n");
                messageBuilder.append("⚠️ RAPPEL: Répondez UNIQUEMENT en utilisant le contenu des documents fournis ci-dessus. ");
                messageBuilder.append("Ne mentionnez JAMAIS la base de données ou les transactions.");
            } else {
                messageBuilder.append("⚠️ ATTENTION: Aucun contenu trouvé dans les documents chargés pour répondre à cette question.\n\n");
                messageBuilder.append("Question: ").append(question);
                messageBuilder.append("\n\n");
                messageBuilder.append("Veuillez informer l'utilisateur que l'information demandée n'est pas disponible dans les documents chargés.");
            }
        } else {
            if (toolResult != null && !toolResult.isEmpty()) {
                messageBuilder.append("Données récupérées de la base de données:\n");
                messageBuilder.append(toolResult);
                messageBuilder.append("\n\nQuestion de l'utilisateur: ").append(question);
            } else {
                messageBuilder.append(question);
            }
        }
        
        return messageBuilder.toString();
    }
    
    /**
     * Génère la réponse en streaming
     */
    private Flux<String> generateResponse(
            List<dev.langchain4j.data.message.ChatMessage> allMessages,
            MessageWindowChatMemory chatMemory,
            UserMessage userMessage) {
        
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
            
            streamingChatLanguageModel.generate(
                allMessages,
                new StreamingResponseHandler<dev.langchain4j.data.message.AiMessage>() {
                    @Override
                    public void onNext(String token) {
                        sink.tryEmitNext(token);
                    }

                    @Override
                    public void onComplete(Response<dev.langchain4j.data.message.AiMessage> response) {
                        chatMemory.add(userMessage);
                        chatMemory.add(response.content());
                        sink.tryEmitComplete();
                    }

                    @Override
                    public void onError(Throwable error) {
                    log.error("Erreur lors de la génération: {}", error.getMessage(), error);
                        chatMemory.add(userMessage);
                        sink.tryEmitError(error);
                    }
                }
            );
            
            return sink.asFlux()
                    .onErrorResume(error -> {
                    log.error("Erreur dans le flux: {}", error.getMessage(), error);
                        return Flux.just("Erreur lors de la génération: " + error.getMessage());
                    });
    }
    
    @GetMapping("/askAgentDirect")
    public Flux<String> chatDirect(@RequestParam(defaultValue = "Bonjour") String question) {
        SystemMessage systemMessage = SystemMessage.from(
            "Tu es un assistant utile. Réponds à la question de l'utilisateur en utilisant le contexte fourni. Réponds TOUJOURS en FRANÇAIS."
        );
        UserMessage userMessage = UserMessage.from(question);
        
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        
        streamingChatLanguageModel.generate(
            List.of(systemMessage, userMessage),
            new StreamingResponseHandler<dev.langchain4j.data.message.AiMessage>() {
                @Override
                public void onNext(String token) {
                    sink.tryEmitNext(token);
                }

                @Override
                public void onComplete(Response<dev.langchain4j.data.message.AiMessage> response) {
                    sink.tryEmitComplete();
                }

                @Override
                public void onError(Throwable error) {
                    sink.tryEmitError(error);
                }
            }
        );
        
        return sink.asFlux();
    }

    /**
     * Endpoint utilisant directement l'agent avec les outils de base de données
     * Note: Ollama ne supporte pas nativement les function calls, donc cet endpoint
     * utilise l'agent avec streaming mais les outils sont appelés manuellement via TransactionToolService
     */
    @GetMapping("/askAgentWithTools")
    public Flux<String> chatWithTools(
            @RequestParam(defaultValue = "Bonjour") String question,
            @RequestParam(required = false, defaultValue = "default") String chatId) {
        try {
            // Utiliser l'agent directement si possible, sinon utiliser l'approche manuelle
            // Pour Ollama, on utilise l'approche manuelle avec les outils
            return chat(question, chatId);
        } catch (Exception e) {
            e.printStackTrace();
            return Flux.just("Erreur lors de l'exécution de l'agent: " + e.getMessage());
        }
    }

    /**
     * Endpoint de diagnostic pour vérifier l'état du RAG
     * Accessible via /ragStatus ou /rag/status
     */
    /**
     * Endpoint utilisant l'orchestration multi-agents (Agentic RAG 2.0)
     * Pipeline: Classification → Retrieval → Reasoning → ReAct → Tool-Use → Verification → Réponse
     */
    @GetMapping("/askAgentMultiAgent")
    public Flux<String> chatMultiAgent(
            @RequestParam(defaultValue = "Bonjour") String question,
            @RequestParam(required = false, defaultValue = "default") String chatId) {
        
        try {
            // Décoder la question
            String decodedQuestion = java.net.URLDecoder.decode(question, java.nio.charset.StandardCharsets.UTF_8);
            if (!decodedQuestion.equals(question)) {
                question = decodedQuestion;
            }
            
            if (multiAgentOrchestrator == null) {
                log.warn("⚠️ MultiAgentOrchestrator non disponible, utilisation du mode classique");
                return chat(question, chatId);
            }
            
            log.info("🎯 Utilisation de l'orchestration multi-agents pour: '{}'", question);
            
            // Orchestrer avec tous les agents
            MultiAgentOrchestrator.OrchestrationResult result = multiAgentOrchestrator.orchestrate(question);
            
            // Sauvegarder dans la mémoire conversationnelle
            MessageWindowChatMemory chatMemory = (MessageWindowChatMemory) chatMemoryProvider.get((Object) chatId);
            chatMemory.add(UserMessage.from(question));
            chatMemory.add(dev.langchain4j.data.message.AiMessage.from(result.getFinalResponse()));
            
            // Retourner la réponse en streaming (simulé)
            return Flux.just(result.getFinalResponse().split(""))
                    .map(s -> s)
                    .delayElements(java.time.Duration.ofMillis(20)); // Simulation du streaming
            
        } catch (Exception e) {
            log.error("Erreur lors de l'orchestration multi-agents: {}", e.getMessage(), e);
            return Flux.just("Erreur: " + e.getMessage());
        }
    }
    
    @GetMapping({"/ragStatus", "/rag/status"})
    public String ragStatus() {
        StringBuilder status = new StringBuilder();
        status.append("═══════════════════════════════════════════════════════════\n");
        status.append("📊 ÉTAT DU SYSTÈME RAG\n");
        status.append("═══════════════════════════════════════════════════════════\n\n");
        
        // État des composants
        status.append("🔧 COMPOSANTS:\n");
        status.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        status.append("EmbeddingModel: ").append(embeddingModel != null ? "✅ Disponible" : "❌ Non disponible").append("\n");
        status.append("EmbeddingStore: ").append(embeddingStore != null ? "✅ Disponible" : "❌ Non disponible").append("\n\n");
        
        if (embeddingStore == null || embeddingModel == null) {
            status.append("⚠️ ATTENTION: Le RAG n'est pas complètement configuré!\n");
            status.append("   Vérifiez que:\n");
            status.append("   - Ollama est démarré et accessible\n");
            status.append("   - PostgreSQL est démarré et accessible\n");
            status.append("   - Les configurations dans application.properties sont correctes\n");
            return status.toString();
        }
        
        // Tests
        status.append("🧪 TESTS:\n");
        status.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        try {
            // Tester une recherche simple
            log.info("Test d'embedding...");
            dev.langchain4j.data.embedding.Embedding testEmbedding = embeddingModel.embed("test").content();
            status.append("Test d'embedding: ✅ Réussi\n");
            status.append("   Dimension: ").append(testEmbedding.dimension()).append("\n\n");
            
            // Essayer de compter les documents dans le store
            try {
                log.info("Comptage des documents dans le vector store...");
                java.lang.reflect.Method findRelevantMethod = embeddingStore.getClass()
                        .getMethod("findRelevant", 
                                dev.langchain4j.data.embedding.Embedding.class, 
                                int.class, 
                                double.class);
                
                @SuppressWarnings("unchecked")
                List<?> results = (List<?>) findRelevantMethod.invoke(
                        embeddingStore, 
                        testEmbedding, 
                        100,  // Chercher jusqu'à 100 résultats pour compter
                        0.0    // Score minimum 0 pour tout récupérer
                );
                
                int documentCount = results != null ? results.size() : 0;
                status.append("Documents dans le vector store: ").append(documentCount).append("\n");
                
                if (documentCount == 0) {
                    status.append("\n⚠️ ATTENTION: Aucun document trouvé dans le vector store!\n");
                    status.append("   Vérifiez que:\n");
                    status.append("   - Les documents sont dans: src/main/resources/docs/\n");
                    status.append("   - Les documents ont été chargés au démarrage (vérifiez les logs)\n");
                    status.append("   - PostgreSQL contient bien les données\n");
                } else {
                    status.append("   ✅ Des documents sont disponibles pour le RAG\n");
                }
            } catch (Exception e) {
                status.append("❌ Erreur lors du comptage des documents: ").append(e.getMessage()).append("\n");
                log.error("Erreur lors du comptage: {}", e.getMessage(), e);
            }
        } catch (Exception e) {
            status.append("❌ Erreur lors du test d'embedding: ").append(e.getMessage()).append("\n");
            log.error("Erreur lors du test: {}", e.getMessage(), e);
        }
        
        status.append("\n═══════════════════════════════════════════════════════════\n");
        status.append("📝 CONFIGURATION:\n");
        status.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        status.append("maxResults: ").append(maxResults).append("\n");
        status.append("minScore: ").append(minScore).append("\n");
        status.append("═══════════════════════════════════════════════════════════\n");
        
        return status.toString();
    }
}