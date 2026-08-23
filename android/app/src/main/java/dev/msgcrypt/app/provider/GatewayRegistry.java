package dev.msgcrypt.app.provider;

import android.content.Context;

import dev.msgcrypt.app.model.Account;
import dev.msgcrypt.app.model.Provider;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GatewayRegistry implements AutoCloseable {
    private final Context context;
    private final Map<String, MessagingGateway> gateways = new ConcurrentHashMap<>();
    private volatile GatewayListener listener;

    public GatewayRegistry(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(GatewayListener listener) {
        this.listener = listener;
        for (MessagingGateway gateway : gateways.values()) gateway.setListener(listener);
    }

    public MessagingGateway forAccount(Account account) {
        return gateways.computeIfAbsent(account.id, ignored -> create(account));
    }

    public MessagingGateway existing(String accountId) {
        return gateways.get(accountId);
    }

    public void remove(String accountId) {
        MessagingGateway gateway = gateways.remove(accountId);
        if (gateway != null) gateway.close();
    }

    private MessagingGateway create(Account account) {
        File root = new File(context.getFilesDir(), account.provider == Provider.WHATSAPP
                ? "whatsapp/" + account.id : "telegram/" + account.id);
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("Cannot create account directory");
        MessagingGateway gateway;
        if (account.provider == Provider.WHATSAPP) {
            gateway = new WhatsAppGateway(root);
        } else {
            gateway = new TelegramGateway(root, account.telegramApiId, account.telegramApiHash);
        }
        gateway.setListener(listener);
        return gateway;
    }

    @Override
    public void close() {
        for (MessagingGateway gateway : gateways.values()) gateway.close();
        gateways.clear();
    }
}
