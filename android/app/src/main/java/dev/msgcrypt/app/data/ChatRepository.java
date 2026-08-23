package dev.msgcrypt.app.data;

import android.content.ContentValues;
import android.database.Cursor;

import dev.msgcrypt.app.model.Chat;
import dev.msgcrypt.app.model.Message;
import dev.msgcrypt.app.model.SecureState;

import java.util.ArrayList;
import java.util.List;

public final class ChatRepository {
    private final MsgCryptDatabase database;

    public ChatRepository(MsgCryptDatabase database) {
        this.database = database;
    }

    public synchronized void upsert(Chat chat) {
        ContentValues values = new ContentValues();
        values.put("account_id", chat.accountId);
        values.put("remote_id", chat.remoteId);
        values.put("title", chat.title);
        values.put("last_text", chat.lastText);
        values.put("last_at", chat.lastAt);
        values.put("unread_count", chat.unreadCount);
        values.put("secure_state", chat.secureState.name());
        android.database.sqlite.SQLiteDatabase db = database.getWritableDatabase();
        int changed = db.update("chats", values, "account_id=? AND remote_id=?",
                new String[]{chat.accountId, chat.remoteId});
        if (changed == 0) db.insertOrThrow("chats", null, values);
    }

    public synchronized void setSecureState(String accountId, String remoteId, SecureState state) {
        ContentValues values = new ContentValues();
        values.put("secure_state", state.name());
        database.getWritableDatabase().update("chats", values,
                "account_id=? AND remote_id=?", new String[]{accountId, remoteId});
    }

    public synchronized List<Chat> chats(String accountId) {
        List<Chat> result = new ArrayList<>();
        try (Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT account_id,remote_id,title,last_text,last_at,unread_count,secure_state " +
                        "FROM chats WHERE account_id=? ORDER BY last_at DESC,title COLLATE NOCASE",
                new String[]{accountId})) {
            while (cursor.moveToNext()) {
                result.add(new Chat(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getLong(4), cursor.getInt(5),
                        SecureState.valueOf(cursor.getString(6))));
            }
        }
        return result;
    }

    public synchronized void saveMessage(Message message, String chatTitle) {
        android.database.sqlite.SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            Chat existing = findChat(db, message.accountId, message.remoteChatId);
            String title = existing == null ? chatTitle : existing.title;
            SecureState state = existing == null ? SecureState.NONE : existing.secureState;
            upsert(new Chat(message.accountId, message.remoteChatId, title, message.text,
                    message.sentAt, existing == null ? 0 : existing.unreadCount, state));

            ContentValues values = new ContentValues();
            values.put("id", message.id);
            values.put("account_id", message.accountId);
            values.put("remote_chat_id", message.remoteChatId);
            values.put("provider_message_id", message.providerMessageId);
            values.put("text", message.text);
            values.put("sent_at", message.sentAt);
            values.put("outgoing", message.outgoing ? 1 : 0);
            values.put("security", message.security.name());
            db.insertWithOnConflict("messages", null, values,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<Message> messages(String accountId, String remoteId, int limit) {
        List<Message> result = new ArrayList<>();
        int safeLimit = Math.max(1, Math.min(500, limit));
        try (Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT id,account_id,remote_chat_id,provider_message_id,text,sent_at,outgoing,security " +
                        "FROM messages WHERE account_id=? AND remote_chat_id=? " +
                        "ORDER BY sent_at DESC,id DESC LIMIT " + safeLimit,
                new String[]{accountId, remoteId})) {
            while (cursor.moveToNext()) {
                result.add(0, new Message(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4), cursor.getLong(5),
                        cursor.getInt(6) != 0, Message.Security.valueOf(cursor.getString(7))));
            }
        }
        return result;
    }

    private static Chat findChat(android.database.sqlite.SQLiteDatabase db, String accountId, String remoteId) {
        try (Cursor cursor = db.rawQuery(
                "SELECT account_id,remote_id,title,last_text,last_at,unread_count,secure_state " +
                        "FROM chats WHERE account_id=? AND remote_id=?", new String[]{accountId, remoteId})) {
            if (!cursor.moveToFirst()) return null;
            return new Chat(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getLong(4), cursor.getInt(5),
                    SecureState.valueOf(cursor.getString(6)));
        }
    }
}
