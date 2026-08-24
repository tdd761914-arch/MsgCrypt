package dev.msgcrypt.app.provider;

import android.os.Build;

import dev.msgcrypt.app.model.Account;
import dev.msgcrypt.app.model.AuthState;
import dev.msgcrypt.app.model.Chat;
import dev.msgcrypt.app.model.SecureState;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/** Telegram transport backed by the prebuilt Android TDLib JNI AAR. */
public final class TelegramGateway extends UnavailableGateway {
    private final File root;
    private final int apiId;
    private final String apiHash;
    private final Map<Long, String> titles = new ConcurrentHashMap<>();

    private volatile Client client;
    private volatile String accountId;
    private volatile String phoneNumber;
    private volatile boolean phoneSubmitted;
    private volatile boolean loggingOut;

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
        loggingOut = false;
        phoneSubmitted = false;
        try {
            requireDirectory(root);
            requireDirectory(new File(root, "db"));
            requireDirectory(new File(root, "files"));
            Client.setLogMessageHandler(1, (level, message) -> { });
            emitState(AuthState.CONNECTING, "");
            client = Client.create(this::onUpdate,
                    error -> report("Ошибка обработчика Telegram", error),
                    error -> report("Ошибка Telegram", error));
            return CompletableFuture.completedFuture(null);
        } catch (Throwable error) {
            client = null;
            report("Не удалось запустить TDLib", error);
            return failed("Не удалось загрузить Android TDLib: " + message(error));
        }
    }

    @Override
    public CompletableFuture<Void> submitCode(String accountId, String code) {
        if (code == null || code.isBlank()) return failed("Введите код Telegram");
        return send(new TdApi.CheckAuthenticationCode(code.trim())).thenApply(ok -> null);
    }

    @Override
    public CompletableFuture<Void> submitPassword(String accountId, String password) {
        if (password == null || password.isBlank()) return failed("Введите пароль 2FA");
        return send(new TdApi.CheckAuthenticationPassword(password)).thenApply(ok -> null);
    }

    private void onUpdate(TdApi.Object object) {
        if (object instanceof TdApi.UpdateAuthorizationState) {
            handleAuthorizationState(((TdApi.UpdateAuthorizationState) object).authorizationState);
        } else if (object instanceof TdApi.UpdateNewChat) {
            upsertChat(((TdApi.UpdateNewChat) object).chat);
        } else if (object instanceof TdApi.UpdateChatTitle) {
            TdApi.UpdateChatTitle update = (TdApi.UpdateChatTitle) object;
            titles.put(update.chatId, update.title);
        } else if (object instanceof TdApi.UpdateChatLastMessage) {
            TdApi.UpdateChatLastMessage update = (TdApi.UpdateChatLastMessage) object;
            upsertLast(update.chatId, update.lastMessage);
        } else if (object instanceof TdApi.UpdateNewMessage) {
            emitMessage(((TdApi.UpdateNewMessage) object).message);
        }
    }

    private synchronized void handleAuthorizationState(TdApi.AuthorizationState state) {
        authorizationChanged(state);
    }

    private void authorizationChanged(TdApi.AuthorizationState state) {
        if (state instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            TdApi.SetTdlibParameters parameters = new TdApi.SetTdlibParameters(
                    false,
                    new File(root, "db").getAbsolutePath(),
                    new File(root, "files").getAbsolutePath(),
                    accountId.getBytes(StandardCharsets.UTF_8),
                    true,
                    true,
                    true,
                    false,
                    apiId,
                    apiHash,
                    "ru",
                    Build.MANUFACTURER + " " + Build.MODEL,
                    "Android " + Build.VERSION.RELEASE,
                    "MsgCrypt 0.1.0");
            authRequest(parameters, "Не удалось настроить TDLib");
        } else if (state instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            emitState(AuthState.WAITING_PHONE, "");
            if (!phoneSubmitted) {
                phoneSubmitted = true;
                authRequest(new TdApi.SetAuthenticationPhoneNumber(phoneNumber, null),
                        "Telegram отклонил номер телефона");
            }
        } else if (state instanceof TdApi.AuthorizationStateWaitCode) {
            emitState(AuthState.WAITING_CODE, "");
        } else if (state instanceof TdApi.AuthorizationStateWaitPassword) {
            TdApi.AuthorizationStateWaitPassword password = (TdApi.AuthorizationStateWaitPassword) state;
            emitState(AuthState.WAITING_PASSWORD, password.passwordHint == null ? "" : password.passwordHint);
        } else if (state instanceof TdApi.AuthorizationStateReady) {
            emitState(AuthState.READY, phoneNumber);
        } else if (state instanceof TdApi.AuthorizationStateLoggingOut
                || state instanceof TdApi.AuthorizationStateClosing) {
            emitState(AuthState.CONNECTING, "Закрытие");
        } else if (state instanceof TdApi.AuthorizationStateClosed) {
            client = null;
            if (loggingOut) emitState(AuthState.LOGGED_OUT, "");
        }
    }

    private void authRequest(TdApi.Function<TdApi.Ok> request, String failureMessage) {
        send(request).whenComplete((ok, error) -> {
            if (error != null) report(failureMessage, unwrap(error));
        });
    }

    @Override
    public CompletableFuture<Void> loadChats(String accountId) {
        return send(new TdApi.LoadChats(new TdApi.ChatListMain(), 100)).handle((ok, error) -> {
            if (error != null && !message(unwrap(error)).contains("404")) {
                throw new CompletionException(unwrap(error));
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> loadHistory(String accountId, String chatId, int limit) {
        long id;
        try {
            id = Long.parseLong(chatId);
        } catch (NumberFormatException error) {
            return failed("Некорректный chat id");
        }
        int safe = Math.max(1, Math.min(100, limit));
        return send(new TdApi.GetChatHistory(id, 0, 0, safe, false)).thenAccept(result -> {
            if (result.messages != null) {
                for (TdApi.Message message : result.messages) emitMessage(message);
            }
        });
    }

    @Override
    public CompletableFuture<String> sendText(String accountId, String chatId, String text) {
        long id;
        try {
            id = Long.parseLong(chatId);
        } catch (NumberFormatException error) {
            return failed("Некорректный chat id");
        }
        TdApi.InputMessageText content = new TdApi.InputMessageText(
                new TdApi.FormattedText(text, new TdApi.TextEntity[0]), null, true);
        TdApi.SendMessage request = new TdApi.SendMessage(id, null, null, null, null, content);
        return send(request).thenApply(message -> Long.toString(message.id));
    }

    @Override
    public CompletableFuture<Void> logOut(String accountId) {
        loggingOut = true;
        return send(new TdApi.LogOut()).thenApply(ok -> null);
    }

    @SuppressWarnings("unchecked")
    private <T extends TdApi.Object> CompletableFuture<T> send(TdApi.Function<T> request) {
        Client value = client;
        if (value == null) return failed("Telegram не подключён");
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            value.send(request, object -> {
                if (object instanceof TdApi.Error) {
                    TdApi.Error error = (TdApi.Error) object;
                    future.completeExceptionally(new IllegalStateException(
                            "Telegram " + error.code + ": " + error.message));
                } else {
                    future.complete((T) object);
                }
            }, future::completeExceptionally);
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    private void upsertChat(TdApi.Chat chat) {
        if (chat == null) return;
        titles.put(chat.id, chat.title);
        String last = text(chat.lastMessage);
        long at = chat.lastMessage == null ? 0 : chat.lastMessage.date;
        GatewayListener target = listener;
        if (target != null) {
            target.onChat(new Chat(accountId, Long.toString(chat.id), chat.title,
                    last, at, chat.unreadCount, SecureState.NONE));
        }
    }

    private void upsertLast(long chatId, TdApi.Message message) {
        GatewayListener target = listener;
        if (target != null) {
            target.onChat(new Chat(accountId, Long.toString(chatId),
                    titles.getOrDefault(chatId, Long.toString(chatId)), text(message),
                    message == null ? 0 : message.date, 0, SecureState.NONE));
        }
    }

    private void emitMessage(TdApi.Message message) {
        if (message == null || !(message.content instanceof TdApi.MessageText)) return;
        TdApi.FormattedText formatted = ((TdApi.MessageText) message.content).text;
        if (formatted == null || formatted.text == null) return;
        GatewayListener target = listener;
        if (target != null) {
            target.onText(accountId, Long.toString(message.chatId),
                    titles.getOrDefault(message.chatId, Long.toString(message.chatId)),
                    Long.toString(message.id), formatted.text, message.date, message.isOutgoing);
        }
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

    private static void requireDirectory(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Не удалось создать каталог " + directory.getName());
        }
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    @Override
    public synchronized void close() {
        listener = null;
        Client value = client;
        client = null;
        if (value != null) {
            try {
                value.send(new TdApi.Close(), ignored -> { }, ignored -> { });
            } catch (Throwable ignored) {
                // The native client may already be closed.
            }
        }
    }
}
