package dev.msgcrypt.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import dev.msgcrypt.app.MsgCryptApplication;
import dev.msgcrypt.app.data.AccountLimit;
import dev.msgcrypt.app.data.TextMessageService;
import dev.msgcrypt.app.model.Account;
import dev.msgcrypt.app.model.Provider;

import java.util.List;

public final class MainActivity extends AppCompatActivity implements TextMessageService.Observer {
    private MsgCryptApplication app;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("MsgCrypt — аккаунты");
        app = (MsgCryptApplication) getApplication();
        ScrollView scroll = new ScrollView(this);
        content = Ui.column(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override protected void onStart() { super.onStart(); app.messages().setObserver(this); render(); }

    private void render() {
        content.removeAllViews();
        List<Account> accounts = app.accounts().all();
        content.addView(Ui.title(this, "Аккаунты " + accounts.size() + "/" + AccountLimit.MAX_TOTAL));
        for (Account account : accounts) {
            Button row = Ui.button(this, account.label + "\n" + account.state.title());
            row.setOnClickListener(view -> open(account));
            row.setOnLongClickListener(view -> { confirmDelete(account); return true; });
            content.addView(row);
        }
        Button add = Ui.button(this, "+ Добавить WhatsApp или Telegram");
        add.setOnClickListener(view -> chooseProvider());
        add.setEnabled(accounts.size() < AccountLimit.MAX_TOTAL);
        content.addView(add);
        content.addView(Ui.body(this, "Новые исходящие сообщения — только MsgCrypt. Долгое нажатие удаляет аккаунт."));
    }

    private void chooseProvider() {
        new AlertDialog.Builder(this).setTitle("Тип аккаунта")
                .setItems(new String[]{"WhatsApp — QR", "Telegram — номер и код"}, (dialog, which) -> {
                    try {
                        Account account = app.accounts().create(which == 0 ? Provider.WHATSAPP : Provider.TELEGRAM);
                        openLogin(account);
                    } catch (Exception error) {
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void open(Account account) {
        if (account.state == dev.msgcrypt.app.model.AuthState.READY) {
            startActivity(new Intent(this, ChatListActivity.class).putExtra("account_id", account.id));
        } else openLogin(account);
    }

    private void openLogin(Account account) {
        startActivity(new Intent(this, LoginActivity.class).putExtra("account_id", account.id));
    }

    private void confirmDelete(Account account) {
        new AlertDialog.Builder(this).setTitle("Удалить " + account.label + "?")
                .setMessage("Сессия, история и ключи этого аккаунта будут удалены с устройства.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (d, w) -> {
                    try { app.deleteAccount(account); render(); }
                    catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
                }).show();
    }

    @Override public void onAccountsChanged() { runOnUiThread(this::render); }
    @Override public void onError(String accountId, String message) { runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show()); }
}

