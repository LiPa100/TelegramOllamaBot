package org.example;

import com.google.gson.*;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.BufferedReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Bot extends TelegramLongPollingBot {
    private static final Map<Long, JsonArray> chatHistories = new ConcurrentHashMap<>(); // Массив истории чата
    private static final HttpClient client = HttpClient.newHttpClient(); // Http Клиент
    private static final Gson gson = new Gson(); // Gson

    public static String getProcess(String arg1, String arg2) { // Выполнить команду и получить вывод в String
        var builder = new ProcessBuilder(arg1, arg2);
        try {
            Process process = builder.start();
            BufferedReader reader = process.inputReader();
            String line;
            StringBuilder strBuilder = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                strBuilder.append(line).append("\n");
            }
            return strBuilder.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String sendToOllama(JsonArray messagesArray, JsonArray tools) { // Метод отправки на сервер Ollama
        JsonObject rootPayload = new JsonObject();
        rootPayload.addProperty("model", "qwen3.5:4b"); // Значение - модель нейросети
        rootPayload.add("messages", messagesArray);
        if (tools != null) {
            rootPayload.add("tools", tools);
        }
        rootPayload.addProperty("stream", false);

        String jsonBody = gson.toJson(rootPayload);

        try {
            HttpRequest request = HttpRequest.newBuilder() // Отправляется на серер
                    .uri(URI.create("http://localhost:11434/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString()); // Получение ответа от сервера
            String rawJson = response.body();

            return response.body();


        } catch (Exception e) {
            throw new RuntimeException("Пиздец, запрос к Олламе обвалился");
        }
    }

    public void sendText(Long user, String text) {
        SendMessage sm = SendMessage.builder()
                .chatId(user.toString())
                .text(text).build();
        try {
            execute(sm);
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public void onUpdateReceived(Update update) { // Когда приходит сообщение
        var msg = update.getMessage();
        var user = msg.getFrom();
        var userId = user.getId();
        System.out.println(user.getFirstName() + " написал: " + msg.getText());
        JsonArray messagesArray = chatHistories.computeIfAbsent(userId, id -> { // Если нету истории -> создаёт новую
            JsonArray newArray = new JsonArray();

            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", "Общайся как собеседник в телеграмме, тебя зовут L1PaSBot"); // Системный промпт
            newArray.add(sysMsg);

            return newArray;
        });
        if (messagesArray.size() > 11) {
            System.out.println("Контекст сообщений вышел за максимально допустимое значение");
        }
        while (messagesArray.size() > 11) { // Чистка памяти (Удаление истории чата)
            messagesArray.remove(1);
        }

        JsonArray tools = new JsonArray();
        JsonObject toolsObj = new JsonObject();
        toolsObj.addProperty("type", "function");

        JsonObject functionObj = new JsonObject();
        functionObj.addProperty("name", "getProcess");
        functionObj.addProperty("description", "Запускает системную команду или утилиту в Ubuntu через ProcessBuilder и возвращает результат выполнения.");

        JsonObject parametersObj = new JsonObject();
        parametersObj.addProperty("type", "object");

        JsonObject propertiesObj = new JsonObject();
        JsonObject arg1Obj = new JsonObject();
        JsonObject arg2Obj = new JsonObject();
        arg1Obj.addProperty("type", "string");
        arg1Obj.addProperty("description", "Первый аргумент команды, например java, ls или ip");
        arg2Obj.addProperty("type", "string");
        arg2Obj.addProperty("description", "Второй аргумент команды, например --version, -la или a");

        JsonArray requiredArray = new JsonArray();
        requiredArray.add("arg1");
        requiredArray.add("arg2");

        toolsObj.add("function", functionObj);
        functionObj.add("parameters", parametersObj);
        parametersObj.add("properties", propertiesObj);
        propertiesObj.add("arg1", arg1Obj);
        propertiesObj.add("arg2", arg2Obj);
        parametersObj.add("required", requiredArray);
        tools.add(toolsObj);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", msg.getText().toString());
        messagesArray.add(userMsg);


        String rawJson = sendToOllama(messagesArray, tools);

        System.out.println("Пришёл ответ от сервера: " + rawJson);
        System.out.println("/n--------------------------------------------------/n");
        System.out.println("Юзер написал: " + msg.getText().toString());

        JsonObject rootObj = JsonParser.parseString(rawJson).getAsJsonObject(); // Эта строчка переводит String в Object, обязательна!
        if (rootObj.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").has("tool_calls")) {
            String toolName = rootObj
                    .getAsJsonArray("choices")       // 1. Достали массив choices
                    .get(0).getAsJsonObject()                    // 2. Взяли первый элемент (индекс 0)
                    .getAsJsonObject("message")
                    .getAsJsonArray("tool_calls")      // 3. Зашли в объект message (через одну 's'!)
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("function")
                    .get("name").getAsString();

            String argsJsonString = rootObj
                    .getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .getAsJsonArray("tool_calls")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("function")
                    .get("arguments").getAsString();


            if (toolName.equals("getProcess")) {
                JsonObject argJson = JsonParser.parseString(argsJsonString).getAsJsonObject();
                String toolArg1 = argJson
                        .get("arg1").getAsString();
                String toolArg2 = argJson
                        .get("arg2").getAsString();

                String commandResult = getProcess(toolArg1, toolArg2);

                System.out.println(commandResult);

                JsonObject assistantToolCallMsg = rootObj.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message");
                messagesArray.add(assistantToolCallMsg);

                JsonObject toolResponseMsg = new JsonObject();
                toolResponseMsg.addProperty("role", "tool");
                toolResponseMsg.addProperty("content", commandResult);
                messagesArray.add(toolResponseMsg);

                String secondResponse = sendToOllama(messagesArray, null);
                JsonObject secondRootObj = JsonParser.parseString(secondResponse).getAsJsonObject();
                String aiAnswer = secondRootObj
                        .getAsJsonArray("choices")       // 1. Достали массив choices
                        .get(0).getAsJsonObject()                    // 2. Взяли первый элемент (индекс 0)
                        .getAsJsonObject("message")      // 3. Зашли в объект message (через одну 's'!)
                        .get("content")                              // 4. Нащупали поле content
                        .getAsString();                              // 5. Превратили в обычную строку
                System.out.println("Нейронка написала: " + aiAnswer + ", отправляю в бот...");
                sendText(userId, aiAnswer);
                JsonObject assistantMsg = new JsonObject(); // Добавление истории в массив
                assistantMsg.addProperty("role", "assistant");
                assistantMsg.addProperty("content", aiAnswer);
                messagesArray.add(assistantMsg);
            }
            System.out.println("/n--------------------------------------------------/n");




            } else {
                String aiAnswer = rootObj
                        .getAsJsonArray("choices")       // 1. Достали массив choices
                        .get(0).getAsJsonObject()                    // 2. Взяли первый элемент (индекс 0)
                        .getAsJsonObject("message")      // 3. Зашли в объект message (через одну 's'!)
                        .get("content")                              // 4. Нащупали поле content
                        .getAsString();                              // 5. Превратили в обычную строку
                System.out.println("Нейронка написала: " + aiAnswer + ", отправляю в бот...");
                sendText(userId, aiAnswer);
                JsonObject assistantMsg = new JsonObject(); // Добавление истории в массив
                assistantMsg.addProperty("role", "assistant");
                assistantMsg.addProperty("content", aiAnswer);
                messagesArray.add(assistantMsg);
            }
            System.out.println("/n--------------------------------------------------/n");


        }




        @Override
        public String getBotToken () {
            return "8727073818:AAE8wsvwXMQ9NiBUKBLPDPJ-CrFnU0LHrZQ";
        }

        @Override
        public String getBotUsername () {
            return "l1pasgandonbot";
        }
    }


