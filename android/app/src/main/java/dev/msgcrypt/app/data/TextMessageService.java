package dev.msgcrypt.app.data;

import android.content.Context;

import dev.msgcrypt.app.crypto.CryptoIdentity;
import dev.msgcrypt.app.crypto.CryptoSession;
import dev.msgcrypt.app.crypto.IdentityKeyStore;
import dev.msgcrypt.app.crypto.ProtocolException;
import dev.msgcrypt.app.crypto.WordCoder;
import dev.msgcrypt.app.model.Account;
import dev.msgcrypt.app.model.AuthState;
import dev.msgcrypt.app.model.Chat;
import dev.msgcrypt.app.model.Message;
import dev.msgcrypt.app.model.SecureState;
import dev.msgcrypt.app.provider.GatewayListener;
import dev.msgcrypt.app.provider.GatewayRegistry;
import dev.msgcrypt.app.provider.MessagingGateway;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class TextMessageService implements GatewayListener {
    public interface Observer {
        default void onAccountsChanged() {}
        default void onQrCode(String accountId, String payload) {}
        default void onChatsChanged(String accountId) {}
        default void onMessagesChanged(String accountId, String chatId) {}
        default void onSecurityChanged(String accountId, String chatId, SecureState state, String fingerprint) {}
        default void onError(String accountId, String message) {}
    }

    private final AccountRepository accounts;
    private final ChatRepository chats;
    private final GatewayRegistry gateways;
    private final IdentityKeyStore identities;
    private final WordCoder wordCoder;
    private final Map<String, CryptoSession> sessions = new ConcurrentHashMap<>();
    private volatile Observer observer = new Observer() {};

    public TextMessageService(Context context, AccountRepository accounts, ChatRepository chats,
                              GatewayRegistry gateways, IdentityKeyStore identities, WordCoder wordCoder) {
        this.accounts = accounts;
        this.chats = chats;
        this.gateways = gateways;
        this.identities = identities;
        this.wordCoder = wordCoder;
        gateways.setListener(this);
    }

    public void setObserver(Observer observer) {
        this.observer = observer == null ? new Observer() {} : observer;
    }

    public CompletableFuture<Void> connect(Account account, String phone) {
        return gateways.forAccount(account).connect(account, phone);
    }

    public CompletableFuture<Void> connectTelegram(Account account, String phone, int apiId, String apiHash) {
        try {
            Account configured = accounts.updateTelegramCredentials(account.id, apiId, apiHash);
            gateways.remove(account.id);
            return gateways.forAccount(configured).connect(configured, phone);
        } catch (Exception error) {
            return failed(error);
        }
    }

    public CompletableFuture<Void> submitCode(Account account, String code) {
        return gateways.forAccount(account).submitCode(account.id, code);
    }

    public CompletableFuture<Void> submitPassword(Account account, String password) {
        return gateways.forAccount(account).submitPassword(account.id, password);
    }

    public CompletableFuture<Void> loadChats(Account account) {
        return gateways.forAccount(account).loadChats(account.id);
    }

    public CompletableFuture<Void> loadHistory(Account account, String chatId, int limit) {
        return gateways.forAccount(account).loadHistory(account.id, chatId, limit);
    }

    public CompletableFuture<Void> beginHandshake(Account account, String chatId) {
        try {
            CryptoSession session = session(account.id, chatId);
            chats.setSecureState(account.id, chatId, SecureState.NEGOTIATING);
            observer.onSecurityChanged(account.id, chatId, SecureState.NEGOTIATING, "");
            return sendCarriers(gateways.forAccount(account), account.id, chatId, session.beginHandshake());
        } catch (Exception error) {
            return failed(error);
        }
    }

    public void verifyPeer(Account account, String chatId) throws Exception {
        CryptoSession session = session(account.id, chatId);
        session.verifyPeer();
        chats.setSecureState(account.id, chatId, SecureState.VERIFIED);
        observer.onSecurityChanged(account.id, chatId, SecureState.VERIFIED, session.peerFingerprint());
    }

    public CompletableFuture<Void> sendEncrypted(Account account, String chatId, String text) {
        try {
            CryptoSession session = session(account.id, chatId);
            List<String> carriers = session.sealText(text);
            MessagingGateway gateway = gateways.forAccount(account);
            return sendCarriers(gateway, account.id, chatId, carriers).thenRun(() -> {
                Message message = new Message(UUID.randomUUID().toString(), account.id, chatId, "", text,
                        Instant.now().getEpochSecond(), true, Message.Security.ENCRYPTED_VERIFIED);
                chats.saveMessage(message, chatId);
                observer.onMessagesChanged(account.id, chatId);
            });
        } catch (Exception error) {
            return failed(error);
        }
    }

    public SecureState secureState(String accountId, String chatId) throws Exception {
        return session(accountId, chatId).state();
    }

    public String fingerprint(String accountId, String chatId) throws Exception {
        return session(accountId, chatId).peerFingerprint();
    }

    @Override
    public void onAuthState(String accountId, AuthState state, String detail) {
        accounts.updateState(accountId, state);
        if (state == AuthState.READY && detail != null && !detail.isBlank()) accounts.updateLabel(accountId, detail);
        observer.onAccountsChanged();
    }

    @Override
    public void onQrCode(String accountId, String qrPayload) {
        accounts.updateState(accountId, AuthState.WAITING_QR);
        observer.onQrCode(accountId, qrPayload);
    }

    @Override
    public void onChat(Chat chat) {
        chats.upsert(chat);
        observer.onChatsChanged(chat.accountId);
    }

    @Override
    public void onText(String accountId, String chatId, String chatTitle, String providerMessageId,
                       String transportText, long timestamp, boolean outgoing) {
        Account account = accounts.find(accountId);
        if (account == null) return;
        try {
            CryptoSession session = session(accountId, chatId);
            CryptoSession.Inbound inbound = session.receive(transportText);
            if (!inbound.outbound.isEmpty()) {
                sendCarriers(gateways.forAccount(account), accountId, chatId, inbound.outbound)
                        .exceptionally(error -> { onError(accountId, "Не удалось ответить на handshake", error); return null; });
            }
            switch (inbound.kind) {
                case NOT_CARRIER:
                    chats.saveMessage(new Message(UUID.randomUUID().toString(), accountId, chatId,
                            providerMessageId, inbound.text, timestamp, outgoing, Message.Security.LEGACY_PLAIN), chatTitle);
                    observer.onMessagesChanged(accountId, chatId);
                    break;
                case KEY_READY:
                    chats.setSecureState(accountId, chatId, SecureState.KEY_READY);
                    observer.onSecurityChanged(accountId, chatId, SecureState.KEY_READY, inbound.fingerprint);
                    break;
                case KEY_CHANGED:
                    chats.setSecureState(accountId, chatId, SecureState.KEY_CHANGED);
                    observer.onSecurityChanged(accountId, chatId, SecureState.KEY_CHANGED, inbound.fingerprint);
                    break;
                case TEXT:
                    chats.saveMessage(new Message(inbound.messageId.toString(), accountId, chatId,
                            providerMessageId, inbound.text, inbound.sentAt, outgoing,
                            inbound.verified ? Message.Security.ENCRYPTED_VERIFIED : Message.Security.ENCRYPTED_UNVERIFIED), chatTitle);
                    observer.onMessagesChanged(accountId, chatId);
                    break;
                case CLOSED:
                    chats.setSecureState(accountId, chatId, SecureState.NONE);
                    observer.onSecurityChanged(accountId, chatId, SecureState.NONE, "");
                    break;
                default:
                    break;
            }
        } catch (Exception error) {
            onError(accountId, "Повреждён или неподлинный MsgCrypt-пакет", error);
        }
    }

    @Override
    public void onError(String accountId, String message, Throwable error) {
        observer.onError(accountId, error == null ? message : message + ": " + error.getMessage());
    }

    private CryptoSession session(String accountId, String chatId) throws Exception {
        String key = accountId + "\u0000" + chatId;
        CryptoSession existing = sessions.get(key);
        if (existing != null) return existing;
        CryptoIdentity identity = identities.loadOrCreate(accountId);
        CryptoSession created = new CryptoSession(identity, wordCoder);
        CryptoSession raced = sessions.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }

    private static CompletableFuture<Void> sendCarriers(MessagingGateway gateway, String accountId,
                                                         String chatId, List<String> carriers) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String carrier : carriers) {
            chain = chain.thenCompose(ignored -> gateway.sendText(accountId, chatId, carrier).thenApply(id -> null));
        }
        return chain;
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error instanceof ProtocolException ? error : new ProtocolException(error.getMessage(), error));
        return future;
    }
}
