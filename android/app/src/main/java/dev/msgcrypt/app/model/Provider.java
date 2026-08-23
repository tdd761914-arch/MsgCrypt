package dev.msgcrypt.app.model;

public enum Provider {
    WHATSAPP(1, "WhatsApp"),
    TELEGRAM(2, "Telegram");

    private final int code;
    private final String title;

    Provider(int code, String title) {
        this.code = code;
        this.title = title;
    }

    public int code() {
        return code;
    }

    public String title() {
        return title;
    }

    public static Provider fromCode(int value) {
        for (Provider provider : values()) {
            if (provider.code == value) return provider;
        }
        throw new IllegalArgumentException("Unknown provider: " + value);
    }
}

