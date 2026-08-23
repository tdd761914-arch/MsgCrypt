package dev.msgcrypt.app.ui;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import dev.msgcrypt.app.MsgCryptApplication;
import dev.msgcrypt.app.data.TextMessageService;
import dev.msgcrypt.app.model.Account;
import dev.msgcrypt.app.model.AuthState;
import dev.msgcrypt.app.model.Provider;

public final class LoginActivity extends AppCompatActivity implements TextMessageService.Observer {
    private MsgCryptApplication app;
    private Account account;
    private LinearLayout content;
    private EditText input;
    private EditText apiIdInput;
    private EditText apiHashInput;
    private ImageView qr;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = (MsgCryptApplication) getApplication();
        account = app.accounts().find(getIntent().getStringExtra("account_id"));
        if (account == null) { finish(); return; }
        setTitle("Вход — " + account.provider.title());
        content = Ui.column(this);
        setContentView(content);
        app.messages().setObserver(this);
        render(account.state);
        if (account.provider == Provider.WHATSAPP) connect("");
    }

    @Override protected void onStart() { super.onStart(); app.messages().setObserver(this); }

    private void render(AuthState state) {
        content.removeAllViews();
        content.addView(Ui.title(this, account.label));
        content.addView(Ui.body(this, state.title()));
        if (account.provider == Provider.WHATSAPP) {
            qr = new ImageView(this);
            qr.setAdjustViewBounds(true);
            content.addView(qr, new LinearLayout.LayoutParams(-1, Ui.dp(this, 360)));
            Button retry = Ui.button(this, "Обновить QR");
            retry.setOnClickListener(view -> connect(""));
            content.addView(retry);
            return;
        }
        input = new EditText(this);
        input.setSingleLine(true);
        Button action = Ui.button(this, "Продолжить");
        if (state == AuthState.WAITING_CODE) {
            input.setHint("Код Telegram");
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            action.setOnClickListener(view -> future(app.messages().submitCode(account, input.getText().toString())));
        } else if (state == AuthState.WAITING_PASSWORD) {
            input.setHint("Пароль 2FA");
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            action.setOnClickListener(view -> future(app.messages().submitPassword(account, input.getText().toString())));
        } else {
            apiIdInput = new EditText(this);
            apiIdInput.setHint("Telegram api_id");
            apiIdInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            if (account.telegramApiId > 0) apiIdInput.setText(Integer.toString(account.telegramApiId));
            apiHashInput = new EditText(this);
            apiHashInput.setHint("Telegram api_hash");
            apiHashInput.setSingleLine(true);
            apiHashInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            apiHashInput.setText(account.telegramApiHash);
            input.setHint("Номер телефона: +380…");
            input.setInputType(InputType.TYPE_CLASS_PHONE);
            content.addView(apiIdInput);
            content.addView(apiHashInput);
            action.setOnClickListener(view -> connectTelegram());
        }
        content.addView(input);
        content.addView(action);
    }

    private void connect(String phone) { future(app.messages().connect(account, phone)); }

    private void connectTelegram() {
        try {
            int apiId = Integer.parseInt(apiIdInput.getText().toString().trim());
            future(app.messages().connectTelegram(account, input.getText().toString(), apiId,
                    apiHashInput.getText().toString()));
        } catch (NumberFormatException error) {
            Toast.makeText(this, "api_id должен быть числом", Toast.LENGTH_LONG).show();
        }
    }

    private void future(java.util.concurrent.CompletableFuture<Void> future) {
        future.exceptionally(error -> { runOnUiThread(() -> Toast.makeText(this, root(error), Toast.LENGTH_LONG).show()); return null; });
    }

    @Override
    public void onAccountsChanged() {
        runOnUiThread(() -> {
            Account next = app.accounts().find(account.id);
            if (next == null) { finish(); return; }
            account = next;
            if (account.state == AuthState.READY) {
                startActivity(new android.content.Intent(this, ChatListActivity.class).putExtra("account_id", account.id));
                finish();
            } else render(account.state);
        });
    }

    @Override public void onQrCode(String accountId, String payload) {
        if (!account.id.equals(accountId)) return;
        runOnUiThread(() -> { try { qr.setImageBitmap(qr(payload)); } catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); } });
    }

    @Override public void onError(String accountId, String message) {
        if (account.id.equals(accountId)) runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private Bitmap qr(String text) throws Exception {
        int size = 900;
        BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
        Bitmap image = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) image.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
        return image;
    }

    private static String root(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }
}
