package net.youssfi.transactionservice.agents;

import dev.langchain4j.service.SystemMessage;

import java.util.stream.Stream;

public interface TransactionAIAgent {
    @SystemMessage("""
            You are a helpful assistant. Answer the user's question using the provided context.
            """)
    Stream<String> chat(String question);
}
