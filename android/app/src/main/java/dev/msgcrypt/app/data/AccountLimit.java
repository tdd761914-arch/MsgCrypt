package dev.msgcrypt.app.data;

import dev.msgcrypt.app.model.Provider;

public final class AccountLimit {
    public static final int MAX_PER_PROVIDER = 2;
    public static final int MAX_TOTAL = 4;

    private AccountLimit() {}

    public static int firstFreeSlot(Provider provider, boolean slotOneUsed, boolean slotTwoUsed) {
        if (!slotOneUsed) return 1;
        if (!slotTwoUsed) return 2;
        throw new LimitReachedException("Можно добавить не больше 2 аккаунтов " + provider.title());
    }

    public static final class LimitReachedException extends IllegalStateException {
        public LimitReachedException(String message) {
            super(message);
        }
    }
}

