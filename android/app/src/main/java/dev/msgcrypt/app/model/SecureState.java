package dev.msgcrypt.app.model;

public enum SecureState {
    NONE,
    NEGOTIATING,
    KEY_READY,
    VERIFIED,
    KEY_CHANGED,
    ERROR
}

