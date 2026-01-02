package net.youssfi.transactionservice.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.service.AiServices;
import net.youssfi.transactionservice.agents.TransactionAIAgent;
import net.youssfi.transactionservice.agents.TransactionAiTools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class AiConfig {

    /*  @Bean
     public Tokenizer tokenizer() {
         // Tokenizer simple pour Ollama (approximation: 1 token ≈ 4 caractères)
         return new Tokenizer() {
             @Override
             public int estimateTokenCountInText(String text) {
                 return text.length() / 4;
             }

             @Override
             public int estimateTokenCountInMessage(dev.langchain4j.data.message.ChatMessage message) {
                 return estimateTokenCountInText(message.text());
             }

             @Override
             public int estimateTokenCountInToolExecutionRequests(Iterable<dev.langchain4j.agent.tool.ToolExecutionRequest> toolExecutionRequests) {
                 int count = 0;
                 for (dev.langchain4j.agent.tool.ToolExecutionRequest request : toolExecutionRequests) {
                     count += estimateTokenCountInText(request.name());
                     if (request.arguments() != null) {
                         count += estimateTokenCountInText(request.arguments());
                     }
                 }
                 return count;
             }
         };
     }
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        // Mémoire conversationnelle persistante par chatId
        // Utilise une Map thread-safe pour stocker les mémoires par chatId
        Map<String, MessageWindowChatMemory> memories = new ConcurrentHashMap<>();
        
        return chatId -> {
            // Convertir chatId en String pour la clé de la Map
            String chatIdStr = chatId != null ? chatId.toString() : "default";
            // Retourner la mémoire existante ou en créer une nouvelle pour ce chatId
            // Cela garantit que chaque utilisateur/session a sa propre mémoire persistante
            return memories.computeIfAbsent(chatIdStr, id -> MessageWindowChatMemory.withMaxMessages(20));
        };
        
    }

    /**
     * Configuration de l'agent avec les outils de base de données
     * L'agent peut maintenant interagir directement avec la base de données via les outils
     * Note: Ollama ne supporte pas nativement les function calls, donc les outils sont
     * appelés manuellement via TransactionToolService dans le contrôleur
     * Cette configuration permet d'utiliser l'agent si un modèle supportant les function calls est utilisé
     * Le bean est optionnel - si la création échoue, l'application continuera à fonctionner avec l'approche manuelle
     */
    // @Bean - Commenté car Ollama ne supporte pas les function calls nativement
    // L'agent est créé manuellement dans le contrôleur si nécessaire
    /*
    @Bean
    public TransactionAIAgent transactionAIAgent(
            ChatLanguageModel chatLanguageModel,
            ChatMemoryProvider chatMemoryProvider,
            TransactionAiTools transactionAiTools) {
        return AiServices.builder(TransactionAIAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(transactionAiTools)
                .build();
    }
    */
}