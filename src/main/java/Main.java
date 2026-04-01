import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        ChatCompletionTool shellTool = ChatCompletionTool.builder().
                type(JsonValue.from("function")).
                function(FunctionDefinition.builder().name("Bash").
                        description("Execute a shell command.").
                        parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object")).
                                putAdditionalProperty("properties",
                                        JsonValue.from(Map.of("command", Map.of("type", "string",
                                                "description", "The command to execute"))))
                                .putAdditionalProperty("required",
                                        JsonValue.from(List.of(JsonValue.from("command")))
                                )
                                .build())
                        .build()).build();
        ChatCompletionTool writeTool = ChatCompletionTool.builder().
                type(JsonValue.from("function")).
                function(FunctionDefinition.builder().name("Write").
                        description("Write content to a file").
                        parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object")).
                                putAdditionalProperty("properties",
                                        JsonValue.from(Map.of("file_path", Map.of("type", "string",
                                                "description", "The path to the file to be written to"),
                                                "content", Map.of(
                                                        "type", "string",
                                                        "description", "The content to write to the file"
                                                )))
                                )
                                .putAdditionalProperty("required",
                                        JsonValue.from(List.of(JsonValue.from("file_path"),
                                                JsonValue.from("content")))
                                )
                                .build())
                        .build()).build();
        List<ChatCompletionMessageParam> customHistory = new ArrayList<>();
        customHistory.add(ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder().content(prompt).build()
        ));
        while(true) {
            ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                    .model("anthropic/claude-haiku-4.5").addTool(readTool)
                    .addTool(writeTool).addTool(shellTool);
            for(ChatCompletionMessageParam item: customHistory) {
                builder.addMessage(item);
            }
            ChatCompletion response = client.chat().completions().create(
                    builder.build()
            );

            if (response.choices().isEmpty()) {
                throw new RuntimeException("no choices in response");
            }

            // You can use print statements as follows for debugging, they'll be visible when running tests.
            System.err.println("Logs from your program will appear here!");
            ChatCompletionMessage modelMsg = response.choices().get(0).message();

            customHistory.add(ChatCompletionMessageParam.ofAssistant(modelMsg.toParam()));
            if(modelMsg.toolCalls().isPresent() && !modelMsg.toolCalls().isEmpty()) {

                Optional<List<ChatCompletionMessageToolCall>> toolCalls = modelMsg.toolCalls();
                for( int i=0;i<toolCalls.stream().count();i++) {
                    ChatCompletionMessageToolCall.Function fun = toolCalls.get().get(i).function();
                    String arguments = fun.arguments();
                    ObjectMapper mapper = new ObjectMapper();
                    Map map = mapper.readValue(arguments, Map.class);

                    if (fun.name().equals("Read")) {
                        String content = Files.readString(Path.of(map.get("file_path").toString()));
                        ChatCompletionMessageParam customAddition =
                                ChatCompletionMessageParam.ofTool(
                                        ChatCompletionToolMessageParam.builder()
                                                .toolCallId(toolCalls.get().get(i).id())
                                                .content(content)
                                                .build());
                        customHistory.add(customAddition);

                    } else if(fun.name().equals("Write")) {
                        String contentToWrite = map.get("content").toString();
                        String path = map.get("file_path").toString();
                        Files.writeString(Path.of(path), contentToWrite);
                        ChatCompletionMessageParam customAddition =
                                ChatCompletionMessageParam.ofTool(
                                        ChatCompletionToolMessageParam.builder()
                                                .toolCallId(toolCalls.get().get(i).id())
                                                .content(contentToWrite)
                                                .build());
                        customHistory.add(customAddition);
                    } else if(fun.name().equals("Bash")) {
                        String cmd = map.get("command").toString();
                        StringBuilder result = new StringBuilder();
                        ProcessBuilder processBuilder = new ProcessBuilder();
                        List<String> builderList = new ArrayList<>();
                        builderList.add(cmd);

                        try {
                            processBuilder.command(builderList);
                            Process process = processBuilder.start();

                            // To read the output list
                            BufferedReader reader = new BufferedReader(new InputStreamReader(
                                    process.getInputStream()));

                            String line;
                            while ((line = reader.readLine()) != null) {
                                result.append(line);
                            }

                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        ChatCompletionMessageParam customAddition =
                                ChatCompletionMessageParam.ofTool(
                                        ChatCompletionToolMessageParam.builder()
                                                .toolCallId(toolCalls.get().get(i).id())
                                                .content(result.toString())
                                                .build());
                        customHistory.add(customAddition);
                    }
                }
            } else {
                System.out.println(modelMsg.content().orElse(""));
                break;
            }
        }
    }
}
