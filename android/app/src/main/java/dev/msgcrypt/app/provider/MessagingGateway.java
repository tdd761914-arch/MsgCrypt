package dev.msgcrypt.app.provider;

import dev.msgcrypt.app.model.Account;

import java.util.concurrent.CompletableFuture;

public interface MessagingGateway extends AutoCloseable {
    void setListener(GatewayListener listener);
    CompletableFuture<Void> connect(Account account, String phoneNumber);
    CompletableFuture<Void> submitCode(String accountId, String code);
    CompletableFuture<Void> submitPassword(String accountId, String password);
    CompletableFuture<Void> loadChats(String accountId);
    CompletableFuture<Void> loadHistory(String accountId, String chatId, int limit);
    CompletableFuture<String> sendText(String accountId, String chatId, String text);
    CompletableFuture<Void> logOut(String accountId);
    @Override void close();
}

