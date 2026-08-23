package dev.msgcrypt.app.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import dev.msgcrypt.app.model.Account;
import dev.msgcrypt.app.model.AuthState;
import dev.msgcrypt.app.model.Provider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AccountRepository {
    private final MsgCryptDatabase database;

    public AccountRepository(MsgCryptDatabase database) {
        this.database = database;
    }

    public synchronized Account create(Provider provider) {
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            boolean one = false;
            boolean two = false;
            try (Cursor cursor = db.rawQuery("SELECT slot FROM accounts WHERE provider=?",
                    new String[]{String.valueOf(provider.code())})) {
                while (cursor.moveToNext()) {
                    int slot = cursor.getInt(0);
                    one |= slot == 1;
                    two |= slot == 2;
                }
            }
            int slot = AccountLimit.firstFreeSlot(provider, one, two);
            Account account = new Account(
                    UUID.randomUUID().toString(), provider, slot,
                    provider.title() + " " + slot, initialState(provider), Instant.now().getEpochSecond(), 0, "");

            ContentValues values = values(account);
            if (db.insertOrThrow("accounts", null, values) == -1) {
                throw new IllegalStateException("Не удалось создать аккаунт");
            }
            db.setTransactionSuccessful();
            return account;
        } finally {
            db.endTransaction();
        }
    }

    private static AuthState initialState(Provider provider) {
        return provider == Provider.WHATSAPP ? AuthState.WAITING_QR : AuthState.WAITING_PHONE;
    }

    public synchronized void updateState(String accountId, AuthState state) {
        ContentValues values = new ContentValues();
        values.put("state", state.name());
        database.getWritableDatabase().update("accounts", values, "id=?", new String[]{accountId});
    }

    public synchronized void updateLabel(String accountId, String label) {
        if (label == null || label.isBlank()) return;
        ContentValues values = new ContentValues();
        values.put("label", label.trim());
        database.getWritableDatabase().update("accounts", values, "id=?", new String[]{accountId});
    }

    public synchronized Account updateTelegramCredentials(String accountId, int apiId, String apiHash) {
        if (apiId <= 0) throw new IllegalArgumentException("Введите корректный Telegram api_id");
        if (apiHash == null || apiHash.trim().length() < 16) throw new IllegalArgumentException("Введите корректный Telegram api_hash");
        ContentValues values = new ContentValues();
        values.put("telegram_api_id", apiId);
        values.put("telegram_api_hash", apiHash.trim());
        database.getWritableDatabase().update("accounts", values, "id=?", new String[]{accountId});
        return find(accountId);
    }

    public synchronized List<Account> all() {
        List<Account> result = new ArrayList<>();
        try (Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT id,provider,slot,label,state,created_at,telegram_api_id,telegram_api_hash FROM accounts ORDER BY provider,slot", null)) {
            while (cursor.moveToNext()) result.add(read(cursor));
        }
        return result;
    }

    public synchronized Account find(String id) {
        try (Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT id,provider,slot,label,state,created_at,telegram_api_id,telegram_api_hash FROM accounts WHERE id=?",
                new String[]{id})) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }
    }

    public synchronized void delete(String id) {
        database.getWritableDatabase().delete("accounts", "id=?", new String[]{id});
    }

    private static ContentValues values(Account account) {
        ContentValues values = new ContentValues();
        values.put("id", account.id);
        values.put("provider", account.provider.code());
        values.put("slot", account.slot);
        values.put("label", account.label);
        values.put("state", account.state.name());
        values.put("telegram_api_id", account.telegramApiId);
        values.put("telegram_api_hash", account.telegramApiHash);
        values.put("created_at", account.createdAt);
        return values;
    }

    private static Account read(Cursor cursor) {
        return new Account(cursor.getString(0), Provider.fromCode(cursor.getInt(1)), cursor.getInt(2),
                cursor.getString(3), AuthState.valueOf(cursor.getString(4)), cursor.getLong(5),
                cursor.getInt(6), cursor.getString(7));
    }
}
