import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonField;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws IOException {
        if (args.length < 2 || !"-p".equals(args[0])) {
            System.err.println("Usage: program -p <prompt>");
            System.exit(1);
        }

        String prompt = args[1];

        String apiKey = System.getenv("OPENROUTER_API_KEY");
        String baseUrl = System.getenv("OPENROUTER_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://openrouter.ai/api/v1";
        }

        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("OPENROUTER_API_KEY is not set");
        }

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        ChatCompletionTool readTool = ChatCompletionTool.builder().
                type(JsonValue.from("function")).
                function(FunctionDefinition.builder().name("Read").
                        description("Read and return the contents of a file").
                        parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object")).
                                putAdditionalProperty("properties",
                                        JsonValue.from(Map.of("file_path", Map.of("type", "string",
                                        "description", "The path to the file to be read"))))
                                .putAdditionalProperty("required",
                                        JsonValue.from(List.of(JsonValue.from("file_path")))
                                )
                                        .build())
                        .build()).build();

        ChatCompletion response = client.chat().completions().create(
                ChatCompletionCreateParams.builder()
                        .model("anthropic/claude-haiku-4.5")
                        .addUserMessage(prompt).addTool(readTool)
                        .build()
        );

        if (response.choices().isEmpty()) {
            throw new RuntimeException("no choices in response");
        }

        // You can use print statements as follows for debugging, they'll be visible when running tests.
        System.err.println("Logs from your program will appear here!");

        // TODO: Uncomment the line below to pass the first stage
        if(response.choices().get(0).message().toolCalls().isPresent()) {
            ChatCompletionMessageToolCall.Function fun = response.choices().get(0).message().toolCalls().get().getFirst().function();
            if(fun.name().equals("Read")) {
                String arguments = fun.arguments();
                ObjectMapper mapper = new ObjectMapper();
                Map map = mapper.readValue(arguments, Map.class);
                String content = Files.readString(Path.of(map.get("file_path").toString()));
                System.out.println(content);

            }
        }
        System.out.print(response.choices().get(0).message().content().orElse(""));
    }
}
