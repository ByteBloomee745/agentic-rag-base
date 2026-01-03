package net.youssfi.transactionservice.config;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.model.Tokenizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Configuration
@Slf4j
public class RagConfig {

    @Value("${rag.postgres.host:localhost}")
    private String postgresHost;

    @Value("${rag.postgres.port:5432}")
    private int postgresPort;

    @Value("${rag.postgres.database:agenticRagDb}")
    private String postgresDatabase;

    @Value("${rag.postgres.user:admin}")
    private String postgresUser;

    @Value("${rag.postgres.password:1234}")
    private String postgresPassword;

    @Value("${rag.postgres.table:data_vs_v3}")
    private String postgresTable;

    @Value("${rag.document.chunk-size:1000}")
    private int chunkSize;

    @Value("${rag.document.chunk-overlap:100}")
    private int chunkOverlap;

    @Value("${rag.retriever.max-results:5}")
    private int maxResults;

    @Value("${rag.retriever.min-score:0.3}")
    private double minScore;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.model-name:llama2}")
    private String ollamaModelName;
    
    @Value("${ollama.embedding-model-name:nomic-embed-text}")
    private String ollamaEmbeddingModelName;

    /**
     * Modèle d'embedding utilisant Ollama
     * Utilise OllamaEmbeddingModelImpl qui appelle directement l'API Ollama
     */
    @Bean
    public EmbeddingModel embeddingModel(OllamaEmbeddingModelImpl ollamaEmbeddingModel) {
        log.info("Activation du RAG avec OllamaEmbeddingModelImpl");
        log.info("Modèle d'embedding: {}", ollamaEmbeddingModelName);
        log.info("Base URL Ollama: {}", ollamaBaseUrl);
        return ollamaEmbeddingModel;
    }

    /**
     * Tokenizer simple pour le découpage des documents
     */
    @Bean
    public Tokenizer tokenizer() {
        // Tokenizer simple: 1 token ≈ 4 caractères
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
            public int estimateTokenCountInMessages(Iterable<dev.langchain4j.data.message.ChatMessage> messages) {
                int count = 0;
                for (dev.langchain4j.data.message.ChatMessage message : messages) {
                    count += estimateTokenCountInMessage(message);
                }
                return count;
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

            @Override
            public int estimateTokenCountInToolSpecifications(Iterable<dev.langchain4j.agent.tool.ToolSpecification> toolSpecifications) {
                int count = 0;
                for (dev.langchain4j.agent.tool.ToolSpecification spec : toolSpecifications) {
                    if (spec.name() != null) {
                        count += estimateTokenCountInText(spec.name());
                    }
                    if (spec.description() != null) {
                        count += estimateTokenCountInText(spec.description());
                    }
                }
                return count;
            }
        };
    }

    /**
     * Store d'embeddings utilisant PostgreSQL avec pgvector
     * Fallback vers InMemoryEmbeddingStore si PostgreSQL n'est pas disponible
     * Ce bean n'est créé que si un EmbeddingModel est disponible
     */
    @Bean
    @ConditionalOnBean(EmbeddingModel.class)
    public EmbeddingStore<TextSegment> embeddingStore(EmbeddingModel embeddingModel) {
        
        log.info("Configuration de l'EmbeddingStore PostgreSQL: {}:{}/{}", postgresHost, postgresPort, postgresDatabase);
        try {
            // Obtenir la dimension en générant un embedding de test
            int dimension;
            try {
                dev.langchain4j.data.embedding.Embedding testEmbedding = embeddingModel.embed("test").content();
                dimension = testEmbedding.dimension();
                log.info("Dimension détectée automatiquement: {}", dimension);
            } catch (Exception e) {
                log.warn("Impossible de déterminer la dimension automatiquement, utilisation de 384 par défaut: {}", e.getMessage());
                dimension = 384; // Dimension par défaut pour la plupart des modèles d'embedding
            }
            
            try {
                EmbeddingStore<TextSegment> pgStore = PgVectorEmbeddingStore.builder()
                        .host(postgresHost)
                        .port(postgresPort)
                        .database(postgresDatabase)
                        .user(postgresUser)
                        .password(postgresPassword)
                        .table(postgresTable)
                        .dimension(dimension)
                        .dropTableFirst(false) // Ne pas supprimer la table à chaque démarrage
                        .build();
                
                log.info("✅ EmbeddingStore PostgreSQL créé avec succès");
                return pgStore;
            } catch (Exception e) {
                log.warn("⚠️ Impossible de se connecter à PostgreSQL: {}", e.getMessage());
                log.warn("   Utilisation d'InMemoryEmbeddingStore en fallback (données perdues au redémarrage)");
                log.warn("   Pour utiliser PostgreSQL, assurez-vous que:");
                log.warn("   1. PostgreSQL est démarré sur {}:{}", postgresHost, postgresPort);
                log.warn("   2. La base de données '{}' existe", postgresDatabase);
                log.warn("   3. L'utilisateur '{}' a les permissions nécessaires", postgresUser);
                log.warn("   4. L'extension pgvector est installée: CREATE EXTENSION IF NOT EXISTS vector;");
                
                // Fallback vers InMemoryEmbeddingStore
                return new InMemoryEmbeddingStore<>();
            }
        } catch (Exception e) {
            log.error("❌ Erreur critique lors de la création de l'EmbeddingStore: {}", e.getMessage(), e);
            log.warn("   Utilisation d'InMemoryEmbeddingStore en fallback");
            return new InMemoryEmbeddingStore<>();
        }
    }

    /**
     * ChatLanguageModel pour la description des images
     */
    @Bean
    public ChatLanguageModel imageDescriptionModel() {
        return OllamaChatModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(ollamaModelName)
                .build();
    }

    /**
     * ApplicationRunner pour charger les documents au démarrage
     * Charge les documents depuis le dossier docs/
     * Ce bean n'est créé que si EmbeddingModel et EmbeddingStore sont disponibles
     */
    @Bean
    @ConditionalOnBean({EmbeddingModel.class, EmbeddingStore.class})
    public ApplicationRunner loadDocumentToVectorStore(
            ChatLanguageModel imageDescriptionModel,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            Tokenizer tokenizer,
            @Value("classpath:/docs") Resource folderResource) {
        return args -> {
            
            try {
                log.info("═══════════════════════════════════════════════════════════");
                log.info("🚀 DÉMARRAGE DU CHARGEMENT DES DOCUMENTS DANS LE VECTOR STORE");
                log.info("═══════════════════════════════════════════════════════════");
                
                // Essayer d'obtenir le dossier docs de différentes manières
                File docsFolder = null;
                try {
                    // Méthode 1: Depuis le classpath (fonctionne en développement)
                    docsFolder = folderResource.getFile();
                    log.info("📁 Dossier trouvé via classpath: {}", docsFolder.getAbsolutePath());
                } catch (Exception e) {
                    // Méthode 2: Depuis le système de fichiers (chemin absolu)
                    String projectPath = System.getProperty("user.dir");
                    docsFolder = new File(projectPath + "/agentic-rag-base/transaction-service/src/main/resources/docs");
                    if (!docsFolder.exists()) {
                        // Méthode 3: Chemin alternatif
                        docsFolder = new File("src/main/resources/docs");
                    }
                    log.info("📁 Tentative avec chemin système: {}", docsFolder.getAbsolutePath());
                }
                
                if (docsFolder == null || !docsFolder.exists() || !docsFolder.isDirectory()) {
                    log.error("❌ ERREUR: Le dossier docs/ n'existe pas ou n'est pas accessible!");
                    log.error("   Chemin recherché: {}", docsFolder != null ? docsFolder.getAbsolutePath() : "null");
                    log.error("   Vérifiez que le dossier existe à: src/main/resources/docs");
                    return;
                }
                
                log.info("✅ Dossier docs/ trouvé: {}", docsFolder.getAbsolutePath());

                // Vérifier que l'embeddingStore n'est pas null
                if (embeddingStore == null) {
                    log.error("❌ ERREUR CRITIQUE: embeddingStore est null!");
                    log.error("   Le RAG ne peut pas fonctionner sans EmbeddingStore.");
                    log.error("   Vérifiez la configuration PostgreSQL ou utilisez InMemoryEmbeddingStore.");
                    return;
                }
                
                DocumentSplitter documentSplitter = DocumentSplitters.recursive(chunkSize, chunkOverlap, tokenizer);
                
                EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                        .documentSplitter(documentSplitter)
                        .embeddingModel(embeddingModel)
                        .embeddingStore(embeddingStore)
                        .build();

                // Charger tous les fichiers du dossier docs/
                File[] files = docsFolder.listFiles();
                if (files == null || files.length == 0) {
                    log.warn("⚠️ Aucun fichier trouvé dans le dossier docs/");
                    log.warn("   Vérifiez que vos fichiers PDF/TXT/images sont dans: {}", docsFolder.getAbsolutePath());
                    return;
                }
                
                log.info("📋 {} fichier(s) trouvé(s) dans le dossier docs/", files.length);
                for (File f : files) {
                    log.info("   - {}", f.getName());
                }

                int totalDocuments = 0;
                int totalSegments = 0;
                for (File file : files) {
                    if (file.isFile()) {
                        try {
                            String fileName = file.getName();
                            String fileNameLower = fileName.toLowerCase();
                            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                            log.info("📄 Traitement du fichier: {}", fileName);
                            
                            if (fileNameLower.endsWith(".pdf")) {
                                int count = loadPdfDocument(file, imageDescriptionModel, ingestor, tokenizer);
                                totalSegments += count;
                                totalDocuments++;
                                log.info("✅ PDF {} traité: {} segments chargés", fileName, count);
                            } else if (fileNameLower.endsWith(".txt")) {
                                List<Document> documents = FileSystemDocumentLoader.loadDocuments(file.toPath());
                                ingestor.ingest(documents);
                                totalSegments += documents.size();
                                totalDocuments++;
                                log.info("✅ TXT {} traité: {} documents chargés", fileName, documents.size());
                            } else if (fileNameLower.matches(".*\\.(png|jpg|jpeg|gif)$")) {
                                loadImageDocument(file, imageDescriptionModel, ingestor);
                                totalSegments++;
                                totalDocuments++;
                                log.info("✅ Image {} traitée", fileName);
                            } else {
                                log.debug("⚠️ Fichier ignoré (format non supporté): {}", fileName);
                            }
                        } catch (Exception e) {
                            log.error("❌ ERREUR lors du traitement du fichier {}: {}", file.getName(), e.getMessage(), e);
                        }
                    }
                }
                
                log.info("═══════════════════════════════════════════════════════════");
                log.info("✅ CHARGEMENT TERMINÉ!");
                log.info("   Documents traités: {}", totalDocuments);
                log.info("   Segments chargés dans le vector store: {}", totalSegments);
                log.info("═══════════════════════════════════════════════════════════");
                
                if (totalSegments == 0) {
                    log.error("❌ ATTENTION: Aucun segment n'a été chargé dans le vector store!");
                    log.error("   Vérifiez les logs ci-dessus pour identifier les erreurs.");
                }
            } catch (Exception e) {
                log.error("❌ ERREUR CRITIQUE lors du chargement des documents: {}", e.getMessage(), e);
                log.error("   Stack trace:", e);
            }
        };
    }

    /**
     * Charge un document PDF en utilisant le parser de LangChain4j
     * Note: Le parser de LangChain4j gère PDFBox en interne, donc on n'a pas besoin
     * d'importer PDFBox directement
     */
    private int loadPdfDocument(File pdfFile, 
                                  ChatLanguageModel chatLanguageModel,
                                  EmbeddingStoreIngestor ingestor,
                                  Tokenizer tokenizer) throws IOException {
        try {
            log.info("   📖 Lecture du PDF: {}", pdfFile.getAbsolutePath());
            
            if (!pdfFile.exists()) {
                log.error("   ❌ Le fichier PDF n'existe pas: {}", pdfFile.getAbsolutePath());
                return 0;
            }
            
            if (!pdfFile.canRead()) {
                log.error("   ❌ Le fichier PDF n'est pas lisible: {}", pdfFile.getAbsolutePath());
                return 0;
            }
            
            // Utiliser ApachePdfBoxDocumentParser pour charger un fichier PDF unique
            ApachePdfBoxDocumentParser pdfParser = new ApachePdfBoxDocumentParser();
            Document document;
            try (FileInputStream inputStream = new FileInputStream(pdfFile)) {
                document = pdfParser.parse(inputStream);
            }
            List<Document> documents = new ArrayList<>();
            documents.add(document);
            log.info("   📄 PDF parsé: {} document(s) extrait(s)", documents.size());
            
            if (documents.isEmpty()) {
                log.warn("   ⚠️ Aucun contenu extrait du PDF {}", pdfFile.getName());
                return 0;
            }
            
            // Compter les caractères avant ingestion
            int totalChars = 0;
            for (Document doc : documents) {
                String text = doc.text();
                if (text != null && !text.trim().isEmpty()) {
                    totalChars += text.length();
                }
            }
            log.info("   📊 Total de {} caractères extraits du PDF", totalChars);
            
            // Ajouter des métadonnées à chaque document avant ingestion
            List<Document> documentsWithMetadata = new ArrayList<>();
            for (Document doc : documents) {
                String text = doc.text();
                if (text != null && !text.trim().isEmpty()) {
                    dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata();
                    metadata.put("source", pdfFile.getName());
                    metadata.put("type", "pdf");
                    metadata.put("file_path", pdfFile.getAbsolutePath());
                    
                    Document documentWithMetadata = new Document(text, metadata);
                    documentsWithMetadata.add(documentWithMetadata);
                }
            }
            
            log.info("   🔄 Ingestion de {} document(s) dans le vector store...", documentsWithMetadata.size());
            
            // IMPORTANT: Utiliser l'ingestor directement - il va découper et créer les embeddings
            int ingestedCount = 0;
            try {
                // Ingester tous les documents - l'ingestor gère le découpage automatiquement
                ingestor.ingest(documentsWithMetadata);
                ingestedCount = documentsWithMetadata.size();
                log.info("   ✅ {} document(s) ingéré(s) avec succès", ingestedCount);
            } catch (Exception e) {
                log.error("   ❌ Erreur lors de l'ingestion globale: {}", e.getMessage(), e);
                log.error("   Stack trace:", e);
                // Fallback: essayer document par document
                log.info("   🔄 Tentative document par document...");
                for (Document doc : documentsWithMetadata) {
                    try {
                        ingestor.ingest(doc);
                        ingestedCount++;
                    } catch (Exception ex) {
                        log.warn("   ⚠️ Erreur lors de l'ingestion d'un document: {}", ex.getMessage());
                    }
                }
            }
            
            log.info("   ✅ PDF {} traité: {} segments ingérés ({} caractères)", 
                    pdfFile.getName(), ingestedCount, totalChars);
            
            if (ingestedCount == 0) {
                log.error("   ❌ AUCUN segment n'a été ingéré du PDF {}!", pdfFile.getName());
            }
            
            return ingestedCount;
        } catch (Exception e) {
            log.error("   ❌ ERREUR lors du traitement du PDF {}: {}", pdfFile.getName(), e.getMessage(), e);
            throw new IOException("Erreur lors du chargement du PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Charge un document image avec génération de description
     */
    private void loadImageDocument(File imageFile,
                                    ChatLanguageModel chatLanguageModel,
                                    EmbeddingStoreIngestor ingestor) throws IOException {
        try {
            BufferedImage bufferedImage = ImageIO.read(imageFile);
            if (bufferedImage == null) {
                log.warn("Impossible de lire l'image: {}", imageFile.getName());
                return;
            }

            // Convertir en base64
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            String format = imageFile.getName().substring(imageFile.getName().lastIndexOf('.') + 1).toLowerCase();
            ImageIO.write(bufferedImage, format, byteArrayOutputStream);
            byte[] imageData = byteArrayOutputStream.toByteArray();
            String imageBase64 = Base64.getEncoder().encodeToString(imageData);

            // Générer une description avec le LLM
            String imageDescription;
            try {
                UserMessage userMessage = UserMessage.from(
                        "Décris cette image en détail. L'image est en base64: " + 
                        imageBase64.substring(0, Math.min(100, imageBase64.length())) + "..."
                );
                Response<AiMessage> response = chatLanguageModel.generate(userMessage);
                imageDescription = response.content().text();
            } catch (Exception e) {
                log.warn("Impossible de générer une description pour l'image: {}", e.getMessage());
                imageDescription = "Image: " + imageFile.getName();
            }

            // Créer le document avec la description
            dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata();
            metadata.put("source", imageFile.getName());
            metadata.put("type", "image");
            
            Document imageDocument = new Document(
                    "[IMAGE: " + imageFile.getName() + "]\n\n" +
                    "Description de l'image:\n" + imageDescription,
                    metadata
            );
            
            ingestor.ingest(imageDocument);
            log.info("Image {} traitée avec succès", imageFile.getName());
        } catch (Exception e) {
            log.error("Erreur lors du traitement de l'image {}: {}", imageFile.getName(), e.getMessage(), e);
        }
    }
}
