package dev.msgcrypt.app.ui;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import dev.msgcrypt.app.MsgCryptApplication;
import dev.msgcrypt.app.data.TextMessageService;
import dev.msgcrypt.app.model.Account;
import dev.msgcrypt.app.model.Message;
import dev.msgcrypt.app.model.SecureState;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class ChatActivity extends AppCompatActivity implements TextMessageService.Observer {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm")
            .withZone(ZoneId.systemDefault());

    private MsgCryptApplication app;
    private Account account;
    private String chatId;
    private String chatTitle;
    private LinearLayout root;
    private LinearLayout messageList;
    private ScrollView scroll;
    private EditText composer;
    private Button send;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = (MsgCryptApplication) getApplication();
        account = app.accounts().find(getIntent().getStringExtra("account_id"));
        chatId = getIntent().getStringExtra("chat_id");
        chatTitle = getIntent().getStringExtra("chat_title");
        if (account == null || chatId == null) { finish(); return; }
        setTitle(chatTitle == null ? chatId : chatTitle);
        root = Ui.column(this);
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        app.messages().setObserver(this);
        render();
        app.messages().loadHistory(account, chatId, 100).exceptionally(error -> {
            showError(root(error));
            return null;
        });
    }

    private void render() {
        root.removeAllViews();
        SecureState state;
        String fingerprint = "";
        try {
            state = app.messages().secureState(account.id, chatId);
            fingerprint = app.messages().fingerprint(account.id, chatId);
        } catch (Exception error) {
            state = SecureState.ERROR;
        }

        root.addView(Ui.body(this, securityText(state, fingerprint)));
        if (state == SecureState.NONE || state == SecureState.ERROR || state == SecureState.KEY_CHANGED) {
            Button handshake = Ui.button(this, state == SecureState.KEY_CHANGED ? "Создать новую защищённую сессию" : "Начать MsgCrypt handshake");
            handshake.setOnClickListener(view -> app.messages().beginHandshake(account, chatId).exceptionally(error -> {
                showError(root(error));
                return null;
            }));
            root.addView(handshake);
        } else if (state == SecureState.KEY_READY) {
            Button verify = Ui.button(this, "Я сверил отпечаток — доверять ключу");
            final String shownFingerprint = fingerprint;
            verify.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle("Подтвердить собеседника?")
                    .setMessage("Сверьте отпечаток другим каналом:\n\n" + shownFingerprint)
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Подтвердить", (dialog, which) -> {
                        try { app.messages().verifyPeer(account, chatId); render(); }
                        catch (Exception error) { showError(error.getMessage()); }
                    }).show());
            root.addView(verify);
        }

        scroll = new ScrollView(this);
        messageList = new LinearLayout(this);
        messageList.setOrientation(LinearLayout.VERTICAL);
        renderMessages(app.chats().messages(account.id, chatId, 200));
        scroll.addView(messageList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout composeRow = new LinearLayout(this);
        composeRow.setOrientation(LinearLayout.HORIZONTAL);
        composer = new EditText(this);
        composer.setHint(state == SecureState.VERIFIED ? "Зашифрованное сообщение" : "Сначала подтвердите ключ");
        composer.setMaxLines(5);
        composer.setEnabled(state == SecureState.VERIFIED);
        composeRow.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));
        send = new Button(this);
        send.setText("➤");
        send.setEnabled(state == SecureState.VERIFIED);
        send.setOnClickListener(view -> send());
        composeRow.addView(send, new LinearLayout.LayoutParams(Ui.dp(this, 64), -2));
        root.addView(composeRow);
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void renderMessages(List<Message> messages) {
        messageList.removeAllViews();
        for (Message message : messages) {
            TextView bubble = Ui.body(this, message.text + "\n" + badge(message) + " · " + TIME.format(Instant.ofEpochSecond(message.sentAt)));
            bubble.setGravity(message.outgoing ? Gravity.END : Gravity.START);
            int left = message.outgoing ? Ui.dp(this, 48) : 0;
            int right = message.outgoing ? 0 : Ui.dp(this, 48);
            bubble.setPadding(left, Ui.dp(this, 8), right, Ui.dp(this, 8));
            messageList.addView(bubble);
        }
    }

    private void send() {
        String text = composer.getText().toString().trim();
        if (text.isEmpty()) return;
        composer.setEnabled(false);
        send.setEnabled(false);
        app.messages().sendEncrypted(account, chatId, text).whenComplete((ignored, error) -> runOnUiThread(() -> {
            composer.setEnabled(true);
            send.setEnabled(true);
            if (error == null) { composer.setText(""); render(); }
            else showError(root(error));
        }));
    }

    private static String securityText(SecureState state, String fingerprint) {
        switch (state) {
            case VERIFIED: return "🔒 MsgCrypt: ключ проверен\n" + fingerprint;
            case KEY_READY: return "🔑 Получен ключ. Сверьте отпечаток:\n" + fingerprint;
            case NEGOTIATING: return "⏳ Ожидание MsgCrypt на другом устройстве";
            case KEY_CHANGED: return "⚠ Ключ собеседника изменился. Отправка заблокирована.";
            case ERROR: return "⚠ Ошибка защищённой сессии";
            default: return "🔓 Защищённая сессия ещё не создана";
        }
    }

    private static String badge(Message message) {
        switch (message.security) {
            case ENCRYPTED_VERIFIED: return "🔒";
            case ENCRYPTED_UNVERIFIED: return "🔐 не проверено";
            case LEGACY_PLAIN: return "обычное входящее";
            default: return "ошибка";
        }
    }

    @Override public void onMessagesChanged(String accountId, String changedChatId) {
        if (account.id.equals(accountId) && chatId.equals(changedChatId)) runOnUiThread(this::render);
    }

    @Override public void onSecurityChanged(String accountId, String changedChatId, SecureState state, String fingerprint) {
        if (account.id.equals(accountId) && chatId.equals(changedChatId)) runOnUiThread(this::render);
    }

    @Override public void onError(String accountId, String message) {
        if (account.id.equals(accountId)) showError(message);
    }

    private void showError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message == null ? "Ошибка" : message, Toast.LENGTH_LONG).show());
    }

    private static String root(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }
}
