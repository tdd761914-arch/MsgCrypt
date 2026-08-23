package dev.msgcrypt.app.model;

import java.util.Objects;

public final class Chat {
    public final String accountId;
    public final String remoteId;
    public final String title;
    public final String lastText;
    public final long lastAt;
    public final int unreadCount;
    public final SecureState secureState;

    public Chat(String accountId, String remoteId, String title, String lastText,
                long lastAt, int unreadCount, SecureState secureState) {
        this.accountId = Objects.requireNonNull(accountId);
        this.remoteId = Objects.requireNonNull(remoteId);
        this.title = title == null || title.isBlank() ? remoteId : title;
        this.lastText = lastText == null ? "" : lastText;
        this.lastAt = lastAt;
        this.unreadCount = Math.max(0, unreadCount);
        this.secureState = Objects.requireNonNull(secureState);
    }
}

