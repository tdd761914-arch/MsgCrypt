package dev.msgcrypt.app;

import android.app.Application;

import dev.msgcrypt.app.crypto.IdentityKeyStore;
import dev.msgcrypt.app.crypto.WordCoder;
import dev.msgcrypt.app.data.AccountRepository;
import dev.msgcrypt.app.data.ChatRepository;
import dev.msgcrypt.app.data.MsgCryptDatabase;
import dev.msgcrypt.app.data.TextMessageService;
import dev.msgcrypt.app.model.Account;
import dev.msgcrypt.app.provider.GatewayRegistry;

import java.io.File;

public final class MsgCryptApplication extends Application {
    private MsgCryptDatabase database;
    private AccountRepository accounts;
    private ChatRepository chats;
    private GatewayRegistry gateways;
    private IdentityKeyStore identities;
    private TextMessageService messages;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            database = new MsgCryptDatabase(this);
            accounts = new AccountRepository(database);
            chats = new ChatRepository(database);
            gateways = new GatewayRegistry(this);
            identities = new IdentityKeyStore(this);
            WordCoder wordCoder = WordCoder.fromJson(getAssets().open("wordcoder-ru.json"));
            messages = new TextMessageService(this, accounts, chats, gateways, identities, wordCoder);
        } catch (Exception error) {
            throw new IllegalStateException("MsgCrypt initialization failed", error);
        }
    }

    public AccountRepository accounts() { return accounts; }
    public ChatRepository chats() { return chats; }
    public TextMessageService messages() { return messages; }

    public void deleteAccount(Account account) throws Exception {
        gateways.remove(account.id);
        identities.delete(account.id);
        accounts.delete(account.id);
        deleteInsideFiles(new File(getFilesDir(), "whatsapp/" + account.id));
        deleteInsideFiles(new File(getFilesDir(), "telegram/" + account.id));
    }

    private void deleteInsideFiles(File target) throws Exception {
        String root = getFilesDir().getCanonicalPath() + File.separator;
        String path = target.getCanonicalPath();
        if (!path.startsWith(root)) throw new SecurityException("Refusing to delete outside app files");
        if (!target.exists()) return;
        File[] children = target.listFiles();
        if (children != null) for (File child : children) deleteInsideFiles(child);
        if (!target.delete()) throw new IllegalStateException("Unable to delete " + target.getName());
    }
}

