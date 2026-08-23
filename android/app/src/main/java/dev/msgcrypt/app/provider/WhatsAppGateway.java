package dev.msgcrypt.app.provider;

import dev.msgcrypt.app.model.Account;
import dev.msgcrypt.app.model.AuthState;
import dev.msgcrypt.app.model.Chat;
import dev.msgcrypt.app.model.SecureState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WhatsAppGateway extends UnavailableGateway {
    private final File root;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile Object manager;
    private volatile String accountId;

    public WhatsAppGateway(File root) {
        this.root = root;
    }

    @Override
    public CompletableFuture<Void> connect(Account account, String ignoredPhone) {
        accountId = account.id;
        return run(() -> {
            ensureManager();
            call(manager, "connect");
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> loadChats(String accountId) {
        return run(() -> {
            ensureManager();
            String json = String.valueOf(call(manager, "listChats"));
            JSONArray list = new JSONArray(json);
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                emitChat(item.optString("id"), item.optString("title"), item.optString("last_text"),
                        item.optLong("last_at"), item.optInt("unread"));
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> loadHistory(String accountId, String chatId, int limit) {
        return run(() -> { ensureManager(); call(manager, "loadHistory", chatId, (long) limit); return null; });
    }

    @Override
    public CompletableFuture<String> sendText(String accountId, String chatId, String text) {
        return run(() -> { ensureManager(); return String.valueOf(call(manager, "sendText", chatId, text)); });
    }

    @Override
    public CompletableFuture<Void> logOut(String accountId) {
        return run(() -> { if (manager != null) call(manager, "logout"); return null; });
    }

    @Override
    public void close() {
        Object value = manager;
        manager = null;
        if (value != null) {
            try { call(value, "close"); } catch (Exception ignored) {}
        }
        io.shutdownNow();
    }

    private void ensureManager() throws Exception {
        if (manager != null) return;
        synchronized (this) {
            if (manager != null) return;
            Class<?> listenerClass = Class.forName("dev.msgcrypt.bridge.whatsbridge.Listener");
            Object proxy = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[]{listenerClass},
                    (object, method, args) -> {
                        if (method.getName().equalsIgnoreCase("onEvent") && args != null && args.length == 1) {
                            onBridgeEvent(String.valueOf(args[0]));
                        }
                        return null;
                    });
            Class<?> entry = Class.forName("dev.msgcrypt.bridge.whatsbridge.Whatsbridge");
            manager = call(entry, "newManager", root.getAbsolutePath(), proxy);
        }
    }

    private void onBridgeEvent(String raw) {
        try {
            JSONObject value = new JSONObject(raw);
            String type = value.optString("type");
            GatewayListener target = listener;
            if (target == null) return;
            switch (type) {
                case "auth":
                    target.onAuthState(accountId, auth(value.optString("state")), value.optString("detail"));
                    break;
                case "qr":
                    target.onQrCode(accountId, value.getString("qr"));
                    break;
                case "chat":
                    emitChat(value.optString("chat_id"), value.optString("chat_title"),
                            value.optString("last_text"), value.optLong("timestamp"), value.optInt("unread"));
                    break;
                case "text":
                    target.onText(accountId, value.getString("chat_id"), value.optString("chat_title"),
                            value.optString("message_id"), value.getString("text"), value.optLong("timestamp"),
                            value.optBoolean("outgoing"));
                    break;
                case "error":
                    target.onError(accountId, value.optString("detail", "WhatsApp error"), null);
                    break;
            }
        } catch (Exception error) {
            GatewayListener target = listener;
            if (target != null) target.onError(accountId, "Некорректное событие WhatsMeow", error);
        }
    }

    private void emitChat(String id, String title, String lastText, long lastAt, int unread) {
        GatewayListener target = listener;
        if (target != null && id != null && !id.isBlank()) {
            target.onChat(new Chat(accountId, id, title, lastText, lastAt, unread, SecureState.NONE));
        }
    }

    private static AuthState auth(String value) {
        if ("ready".equals(value)) return AuthState.READY;
        if ("logged_out".equals(value)) return AuthState.LOGGED_OUT;
        if ("qr".equals(value)) return AuthState.WAITING_QR;
        return AuthState.CONNECTING;
    }

    private <T> CompletableFuture<T> run(ThrowingSupplier<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try { return action.get(); }
            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }, io);
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        for (Method method : type.getMethods()) {
            if (method.getName().equalsIgnoreCase(name) && method.getParameterCount() == args.length) {
                return method.invoke(target instanceof Class<?> ? null : target, args);
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "/" + args.length);
    }

    private interface ThrowingSupplier<T> { T get() throws Exception; }
}

