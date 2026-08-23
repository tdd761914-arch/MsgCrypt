package dev.msgcrypt.app.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public final class MsgCryptDatabase extends SQLiteOpenHelper {
    private static final String NAME = "msgcrypt.db";
    private static final int VERSION = 2;

    public MsgCryptDatabase(Context context) {
        super(context, NAME, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE accounts (" +
                "id TEXT PRIMARY KEY NOT NULL," +
                "provider INTEGER NOT NULL," +
                "slot INTEGER NOT NULL CHECK(slot BETWEEN 1 AND 2)," +
                "label TEXT NOT NULL," +
                "state TEXT NOT NULL," +
                "telegram_api_id INTEGER NOT NULL DEFAULT 0," +
                "telegram_api_hash TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL," +
                "UNIQUE(provider, slot))");

        db.execSQL("CREATE TABLE chats (" +
                "account_id TEXT NOT NULL," +
                "remote_id TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "last_text TEXT NOT NULL DEFAULT ''," +
                "last_at INTEGER NOT NULL DEFAULT 0," +
                "unread_count INTEGER NOT NULL DEFAULT 0," +
                "secure_state TEXT NOT NULL DEFAULT 'NONE'," +
                "PRIMARY KEY(account_id, remote_id)," +
                "FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE)");

        db.execSQL("CREATE TABLE messages (" +
                "id TEXT PRIMARY KEY NOT NULL," +
                "account_id TEXT NOT NULL," +
                "remote_chat_id TEXT NOT NULL," +
                "provider_message_id TEXT NOT NULL DEFAULT ''," +
                "text TEXT NOT NULL," +
                "sent_at INTEGER NOT NULL," +
                "outgoing INTEGER NOT NULL," +
                "security TEXT NOT NULL," +
                "FOREIGN KEY(account_id, remote_chat_id) REFERENCES chats(account_id, remote_id) ON DELETE CASCADE)");
        db.execSQL("CREATE UNIQUE INDEX message_provider_id ON messages(account_id, provider_message_id) " +
                "WHERE provider_message_id <> ''");
        db.execSQL("CREATE INDEX message_timeline ON messages(account_id, remote_chat_id, sent_at, id)");

        db.execSQL("CREATE TABLE crypto_sessions (" +
                "account_id TEXT NOT NULL," +
                "remote_chat_id TEXT NOT NULL," +
                "state TEXT NOT NULL," +
                "encrypted_blob TEXT NOT NULL," +
                "peer_fingerprint TEXT NOT NULL DEFAULT ''," +
                "send_counter INTEGER NOT NULL DEFAULT 0," +
                "receive_counter INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER NOT NULL," +
                "PRIMARY KEY(account_id, remote_chat_id)," +
                "FOREIGN KEY(account_id, remote_chat_id) REFERENCES chats(account_id, remote_id) ON DELETE CASCADE)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE accounts ADD COLUMN telegram_api_id INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE accounts ADD COLUMN telegram_api_hash TEXT NOT NULL DEFAULT ''");
        }
    }
}
