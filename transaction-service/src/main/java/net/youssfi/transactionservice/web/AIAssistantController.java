package net.youssfi.transactionservice.web;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@CrossOrigin("*")
public class AIAssistantController {
    private StreamingChatLanguageModel streamingChatLanguageModel;
    
    public AIAssistantController(StreamingChatLanguageModel streamingChatLanguageModel){
        this.streamingChatLanguageModel = streamingChatLanguageModel;
    }

    @GetMapping("/askAgent")
    public Flux<String> chat(@RequestParam(defaultValue = "Bonjour") String question) {
        SystemMessage systemMessage = SystemMessage.from(
            "You are a helpful assistant. Answer the user's question using the provided context."
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
}