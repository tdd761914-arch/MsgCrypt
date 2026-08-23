package dev.msgcrypt.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import dev.msgcrypt.app.MsgCryptApplication;
import dev.msgcrypt.app.data.TextMessageService;
import dev.msgcrypt.app.model.Account;
import dev.msgcrypt.app.model.Chat;

import java.util.List;

public final class ChatListActivity extends AppCompatActivity implements TextMessageService.Observer {
    private MsgCryptApplication app;
    private Account account;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = (MsgCryptApplication) getApplication();
        account = app.accounts().find(getIntent().getStringExtra("account_id"));
        if (account == null) { finish(); return; }
        setTitle(account.label);
        ScrollView scroll = new ScrollView(this);
        content = Ui.column(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override
    protected void onStart() {
        super.onStart();
        app.messages().setObserver(this);
        render();
        app.messages().loadChats(account).exceptionally(error -> {
            showError(root(error));
            return null;
        });
    }

    private void render() {
        content.removeAllViews();
        List<Chat> chats = app.chats().chats(account.id);
        content.addView(Ui.title(this, "Чаты — " + account.provider.title()));
        if (chats.isEmpty()) content.addView(Ui.body(this, "Чатов пока нет. Список обновляется…"));
        for (Chat chat : chats) {
            String preview = chat.lastText.isBlank() ? "Нет текстовых сообщений" : chat.lastText;
            if (preview.length() > 80) preview = preview.substring(0, 80) + "…";
            Button row = Ui.button(this, chat.title + "\n" + security(chat) + " · " + preview);
            row.setOnClickListener(view -> startActivity(new Intent(this, ChatActivity.class)
                    .putExtra("account_id", account.id)
                    .putExtra("chat_id", chat.remoteId)
                    .putExtra("chat_title", chat.title)));
            content.addView(row);
        }
        Button refresh = Ui.button(this, "Обновить");
        refresh.setOnClickListener(view -> app.messages().loadChats(account).exceptionally(error -> {
            showError(root(error));
            return null;
        }));
        content.addView(refresh);
    }

    private static String security(Chat chat) {
        switch (chat.secureState) {
            case VERIFIED: return "🔒 проверен";
            case KEY_READY: return "🔑 ждёт проверки";
            case NEGOTIATING: return "⏳ handshake";
            case KEY_CHANGED: return "⚠ ключ изменился";
            default: return "шифрование не настроено";
        }
    }

    @Override public void onChatsChanged(String accountId) {
        if (account.id.equals(accountId)) runOnUiThread(this::render);
    }

    @Override public void onError(String accountId, String message) {
        if (account.id.equals(accountId)) showError(message);
    }

    private void showError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private static String root(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }
}
