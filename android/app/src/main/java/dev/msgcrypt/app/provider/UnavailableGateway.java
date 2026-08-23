package dev.msgcrypt.app.provider;

import dev.msgcrypt.app.model.Account;

import java.util.concurrent.CompletableFuture;

abstract class UnavailableGateway implements MessagingGateway {
    protected volatile GatewayListener listener;

    @Override
    public void setListener(GatewayListener listener) {
        this.listener = listener;
    }

    protected static <T> CompletableFuture<T> failed(String message) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(new IllegalStateException(message));
        return result;
    }

    @Override public CompletableFuture<Void> submitCode(String accountId, String code) { return failed("Этот провайдер не принимает код"); }
    @Override public CompletableFuture<Void> submitPassword(String accountId, String password) { return failed("Этот провайдер не принимает пароль"); }
    @Override public CompletableFuture<Void> loadChats(String accountId) { return CompletableFuture.completedFuture(null); }
    @Override public CompletableFuture<Void> loadHistory(String accountId, String chatId, int limit) { return CompletableFuture.completedFuture(null); }
    @Override public CompletableFuture<Void> logOut(String accountId) { return CompletableFuture.completedFuture(null); }
    @Override public void close() {}
}

