package dev.msgcrypt.app.model;

public enum AuthState {
    NEW("Новый"),
    WAITING_QR("Отсканируйте QR"),
    WAITING_PHONE("Введите номер"),
    WAITING_CODE("Введите код"),
    WAITING_PASSWORD("Введите пароль 2FA"),
    CONNECTING("Подключение"),
    READY("Подключён"),
    ERROR("Ошибка"),
    LOGGED_OUT("Вышел");

    private final String title;

    AuthState(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}

