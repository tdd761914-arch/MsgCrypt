package dev.msgcrypt.app.provider;

import dev.msgcrypt.app.model.AuthState;
import dev.msgcrypt.app.model.Chat;

public interface GatewayListener {
    void onAuthState(String accountId, AuthState state, String detail);
    void onQrCode(String accountId, String qrPayload);
    void onChat(Chat chat);
    void onText(String accountId, String chatId, String chatTitle, String providerMessageId,
                String text, long timestamp, boolean outgoing);
    void onError(String accountId, String message, Throwable error);
}

