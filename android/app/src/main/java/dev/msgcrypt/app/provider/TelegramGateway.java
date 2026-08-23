package dev.msgcrypt.app.provider;

import dev.msgcrypt.app.model.Account;
import dev.msgcrypt.app.model.AuthState;
import dev.msgcrypt.app.model.Chat;
import dev.msgcrypt.app.model.SecureState;

import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.ClientInteraction;
import it.tdlight.client.InputParameter;
import it.tdlight.client.ParameterInfo;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;

import java.io.File;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class TelegramGateway extends UnavailableGateway {
    private final File root;
    private final int apiId;
    private final String apiHash;
    private final Map<Long, String> titles = new ConcurrentHashMap<>();
    private final Map<InputParameter, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private volatile SimpleTelegramClientFactory factory;
    private volatile SimpleTelegramClient client;
    private volatile String accountId;
    private volatile String phoneNumber;

    public TelegramGateway(File root, int apiId, String apiHash) {
        this.root = root;
        this.apiId = apiId;
        this.apiHash = apiHash == null ? "" : apiHash;
    }

    @Override
    public synchronized CompletableFuture<Void> connect(Account account, String phone) {
        if (apiId <= 0 || apiHash.isBlank()) return failed("Введите Telegram api_id и api_hash");
        if (phone == null || phone.isBlank()) return failed("Введите номер Telegram в международном формате");
        if (client != null) return CompletableFuture.completedFuture(null);
        accountId = account.id;
        phoneNumber = phone.trim();
        try {
            TDLibSettings settings = TDLibSettings.create(new APIToken(apiId, apiHash));
            settings.setDatabaseDirectoryPath(Paths.get(root.getAbsolutePath(), "db"));
            settings.setDownloadedFilesDirectoryPath(Paths.get(root.getAbsolutePath(), "files"));
            settings.setFileDatabaseEnabled(false);
            settings.setChatInfoDatabaseEnabled(true);
            settings.setMessageDatabaseEnabled(true);
            settings.setApplicationVersion("MsgCrypt 0.1.0");
            settings.setDeviceModel("Android MsgCrypt");

            factory = new SimpleTelegramClientFactory();
            SimpleTelegramClientBuilder builder = factory.builder(settings);
            builder.setClientInteraction(this::requestParameter);
            builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, this::authorizationChanged);
            builder.addUpdateHandler(TdApi.UpdateNewChat.class, update -> upsertChat(update.chat));
            builder.addUpdateHandler(TdApi.UpdateChatTitle.class, update -> titles.put(update.chatId, update.title));
            builder.addUpdateHandler(TdApi.UpdateChatLastMessage.class, update -> upsertLast(update.chatId, update.lastMessage));
            builder.addUpdateHandler(TdApi.UpdateNewMessage.class, update -> emitMessage(update.message));
            builder.addDefaultExceptionHandler(error -> report("Telegram error", error));
            client = builder.build(AuthenticationSupplier.user(phoneNumber));
            emitState(AuthState.CONNECTING, "");
            return CompletableFuture.completedFuture(null);
        } catch (Throwable error) {
            report("Не удалось запустить TDLight", error);
            return failed("Не удалось загрузить TDLight: " + error.getMessage());
        }
    }

    private CompletableFuture<String> requestParameter(InputParameter parameter, ParameterInfo info) {
        CompletableFuture<String> answer = new CompletableFuture<>();
        CompletableFuture<String> old = pending.put(parameter, answer);
        if (old != null) old.completeExceptionally(new IllegalStateException("Superseded auth request"));
        switch (parameter) {
            case ASK_CODE:
                emitState(AuthState.WAITING_CODE, "");
                break;
            case ASK_PASSWORD:
                emitState(AuthState.WAITING_PASSWORD, info == null ? "" : info.toString());
                break;
            case NOTIFY_LINK:
                emitState(AuthState.CONNECTING, info == null ? "" : info.toString());
                answer.complete("");
                pending.remove(parameter, answer);
                break;
            default:
                answer.completeExceptionally(new IllegalStateException("Регистрация новых Telegram-пользователей не поддерживается: " + parameter));
                pending.remove(parameter, answer);
        }
        return answer;
    }

    @Override
    public CompletableFuture<Void> submitCode(String accountId, String code) {
        return answer(InputParameter.ASK_CODE, code, "Telegram ещё не запросил код");
    }

    @Override
    public CompletableFuture<Void> submitPassword(String accountId, String password) {
        return answer(InputParameter.ASK_PASSWORD, password, "Telegram ещё не запросил пароль");
    }

    private CompletableFuture<Void> answer(InputParameter parameter, String value, String missing) {
        CompletableFuture<String> future = pending.remove(parameter);
        if (future == null) return failed(missing);
        if (value == null || value.isBlank()) return failed("Пустое значение");
        future.complete(value.trim());
        return CompletableFuture.completedFuture(null);
    }

    private void authorizationChanged(TdApi.UpdateAuthorizationState update) {
        TdApi.AuthorizationState state = update.authorizationState;
        if (state instanceof TdApi.AuthorizationStateReady) emitState(AuthState.READY, phoneNumber);
        else if (state instanceof TdApi.AuthorizationStateWaitCode) emitState(AuthState.WAITING_CODE, "");
        else if (state instanceof TdApi.AuthorizationStateWaitPassword) emitState(AuthState.WAITING_PASSWORD, "");
        else if (state instanceof TdApi.AuthorizationStateClosing) emitState(AuthState.CONNECTING, "Закрытие");
        else if (state instanceof TdApi.AuthorizationStateClosed) emitState(AuthState.LOGGED_OUT, "");
    }

    @Override
    public CompletableFuture<Void> loadChats(String accountId) {
        SimpleTelegramClient value = client;
        if (value == null) return failed("Telegram не подключён");
        return value.send(new TdApi.LoadChats(new TdApi.ChatListMain(), 100)).handle((ok, error) -> {
            if (error != null && !String.valueOf(error.getMessage()).contains("404")) throw new java.util.concurrent.CompletionException(error);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> loadHistory(String accountId, String chatId, int limit) {
        SimpleTelegramClient value = client;
        if (value == null) return failed("Telegram не подключён");
        long id;
        try { id = Long.parseLong(chatId); } catch (NumberFormatException error) { return failed("Некорректный chat id"); }
        int safe = Math.max(1, Math.min(100, limit));
        return value.send(new TdApi.GetChatHistory(id, 0, 0, safe, false)).thenAccept(result -> {
            if (result.messages != null) for (TdApi.Message message : result.messages) emitMessage(message);
        });
    }

    @Override
    public CompletableFuture<String> sendText(String accountId, String chatId, String text) {
        SimpleTelegramClient value = client;
        if (value == null) return failed("Telegram не подключён");
        long id;
        try { id = Long.parseLong(chatId); } catch (NumberFormatException error) { return failed("Некорректный chat id"); }
        TdApi.InputMessageText content = new TdApi.InputMessageText(
                new TdApi.FormattedText(text, new TdApi.TextEntity[0]), null, true);
        TdApi.SendMessage request = new TdApi.SendMessage(id, null, null, null, null, content);
        return value.sendMessage(request, true).thenApply(message -> Long.toString(message.id));
    }

    @Override
    public CompletableFuture<Void> logOut(String accountId) {
        SimpleTelegramClient value = client;
        return value == null ? CompletableFuture.completedFuture(null) : value.send(new TdApi.LogOut()).thenApply(ok -> null);
    }

    private void upsertChat(TdApi.Chat chat) {
        if (chat == null) return;
        titles.put(chat.id, chat.title);
        String last = text(chat.lastMessage);
        long at = chat.lastMessage == null ? 0 : chat.lastMessage.date;
        GatewayListener target = listener;
        if (target != null) target.onChat(new Chat(accountId, Long.toString(chat.id), chat.title,
                last, at, chat.unreadCount, SecureState.NONE));
    }

    private void upsertLast(long chatId, TdApi.Message message) {
        GatewayListener target = listener;
        if (target != null) target.onChat(new Chat(accountId, Long.toString(chatId), titles.getOrDefault(chatId, Long.toString(chatId)),
                text(message), message == null ? 0 : message.date, 0, SecureState.NONE));
    }

    private void emitMessage(TdApi.Message message) {
        if (message == null || !(message.content instanceof TdApi.MessageText)) return;
        String text = ((TdApi.MessageText) message.content).text.text;
        GatewayListener target = listener;
        if (target != null) target.onText(accountId, Long.toString(message.chatId),
                titles.getOrDefault(message.chatId, Long.toString(message.chatId)), Long.toString(message.id),
                text, message.date, message.isOutgoing);
    }

    private static String text(TdApi.Message message) {
        if (message == null || !(message.content instanceof TdApi.MessageText)) return "";
        TdApi.FormattedText value = ((TdApi.MessageText) message.content).text;
        return value == null || value.text == null ? "" : value.text;
    }

    private void emitState(AuthState state, String detail) {
        GatewayListener target = listener;
        if (target != null) target.onAuthState(accountId, state, detail);
    }

    private void report(String message, Throwable error) {
        GatewayListener target = listener;
        if (target != null) target.onError(accountId, message, error);
    }

    @Override
    public synchronized void close() {
        SimpleTelegramClient value = client;
        client = null;
        if (value != null) {
            try { value.sendClose(); } catch (Throwable ignored) {}
        }
        SimpleTelegramClientFactory currentFactory = factory;
        factory = null;
        if (currentFactory != null) currentFactory.close();
        for (CompletableFuture<String> future : pending.values()) future.cancel(true);
        pending.clear();
    }
}
