package dev.msgcrypt.app.model;

import java.util.Objects;

public final class Account {
    public final String id;
    public final Provider provider;
    public final int slot;
    public final String label;
    public final AuthState state;
    public final long createdAt;
    public final int telegramApiId;
    public final String telegramApiHash;

    public Account(String id, Provider provider, int slot, String label, AuthState state, long createdAt,
                   int telegramApiId, String telegramApiHash) {
        this.id = Objects.requireNonNull(id);
        this.provider = Objects.requireNonNull(provider);
        this.slot = slot;
        this.label = Objects.requireNonNull(label);
        this.state = Objects.requireNonNull(state);
        this.createdAt = createdAt;
        this.telegramApiId = telegramApiId;
        this.telegramApiHash = telegramApiHash == null ? "" : telegramApiHash;
    }

    public Account withState(AuthState next) {
        return new Account(id, provider, slot, label, next, createdAt, telegramApiId, telegramApiHash);
    }
}
