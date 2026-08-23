package dev.msgcrypt.app.model;

import java.util.Objects;

public final class Message {
    public enum Security { ENCRYPTED_VERIFIED, ENCRYPTED_UNVERIFIED, LEGACY_PLAIN, FAILED }

    public final String id;
    public final String accountId;
    public final String remoteChatId;
    public final String providerMessageId;
    public final String text;
    public final long sentAt;
    public final boolean outgoing;
    public final Security security;

    public Message(String id, String accountId, String remoteChatId, String providerMessageId,
                   String text, long sentAt, boolean outgoing, Security security) {
        this.id = Objects.requireNonNull(id);
        this.accountId = Objects.requireNonNull(accountId);
        this.remoteChatId = Objects.requireNonNull(remoteChatId);
        this.providerMessageId = providerMessageId == null ? "" : providerMessageId;
        this.text = Objects.requireNonNull(text);
        this.sentAt = sentAt;
        this.outgoing = outgoing;
        this.security = Objects.requireNonNull(security);
    }
}

