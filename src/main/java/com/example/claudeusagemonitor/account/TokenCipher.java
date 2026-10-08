package com.example.claudeusagemonitor.account;

import com.example.claudeusagemonitor.config.MonitorProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.diagnostics.FailureAnalyzedException;
import org.springframework.stereotype.Component;

/**
 * Шифрование OAuth-токенов в колонках {@code account.access_token} и {@code refresh_token}:
 * AES-256-GCM, ключ из {@code TOKEN_ENCRYPTION_KEY}.
 *
 * <p>Формат значения в базе — {@code v1:} + base64(IV 12 байт ‖ шифротекст ‖ тег 128 бит).
 * Префикс версии оставляет место для смены алгоритма или ротации ключа: строку без
 * известного префикса не нужно угадывать, она просто не читается. IV случайный на каждое
 * шифрование — повтор IV под одним ключом в GCM раскрывает открытый текст.
 *
 * <p>Дополнительные аутентифицированные данные (AAD) — chat_id аккаунта. Без них
 * шифротекст из одной строки можно было бы переставить в другую, и бот честно опрашивал бы
 * чужой аккаунт; с ними такая строка просто не расшифровывается.
 *
 * <p>Пустые значения не шифруются и хранятся как {@code null}: «токена нет» — не секрет,
 * а запрос {@code findMonitored} опирается именно на {@code is not null}.
 *
 * <p>Ни ключ, ни токены не попадают в сообщения исключений и {@link #toString()}.
 */
@Component
public class TokenCipher {

    /** Префикс текущего формата хранения. */
    static final String PREFIX = "v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final String HOWTO = """
            Сгенерируйте ключ и задайте его в TOKEN_ENCRYPTION_KEY (в проде — секрет репозитория \
            с тем же именем): openssl rand -base64 32 или в PowerShell \
            [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)). \
            Ключ, которым уже зашифрованы токены, менять нельзя: со сменой ключа всем \
            пользователям придётся заново прислать /token.""";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public TokenCipher(MonitorProperties properties) {
        this(properties.getTokenEncryptionKey());
    }

    /**
     * @param base64Key base64 от ровно 32 байт
     * @throws InvalidKeyException если ключ не задан, не base64 или не 32 байта
     */
    public TokenCipher(String base64Key) {
        this.key = new SecretKeySpec(decodeKey(base64Key), "AES");
    }

    /**
     * Шифрует токен аккаунта.
     *
     * @return {@code v1:…} или {@code null} для пустого значения
     */
    public String encrypt(String plaintext, long chatId) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(chatId));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(IV_BYTES + sealed.length).put(iv).put(sealed).array();
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            // AES/GCM есть в любой JRE: сюда попадаем только при сломанной криптографии самой JVM.
            // Причину не прикладываем — на всякий случай, чтобы в стектрейс не попало ничего лишнего
            throw new IllegalStateException("Не удалось зашифровать токен: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Расшифровывает значение из базы.
     *
     * @return открытый токен или {@code null}, если в колонке пусто
     * @throws UnreadableTokenException если значение не в формате v1, зашифровано другим
     *                                  ключом, подменено или принадлежит другому chat_id
     */
    public String decrypt(String stored, long chatId) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            throw new UnreadableTokenException("значение не в формате " + PREFIX + " (не зашифровано или неизвестная версия)");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new UnreadableTokenException("после префикса " + PREFIX + " не base64");
        }
        if (raw.length < IV_BYTES + TAG_BITS / 8) {
            throw new UnreadableTokenException("значение короче IV и тега");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            cipher.updateAAD(aad(chatId));
            byte[] plain = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // AEADBadTagException: другой ключ, подмена байтов или строка чужого chat_id
            throw new UnreadableTokenException("не расшифровывается: другой ключ, подмена или чужая строка");
        }
    }

    @Override
    public String toString() {
        return "TokenCipher[AES-256-GCM, " + PREFIX + "]";
    }

    private static byte[] aad(long chatId) {
        return ByteBuffer.allocate(Long.BYTES).putLong(chatId).array();
    }

    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new InvalidKeyException("Не задан ключ шифрования токенов TOKEN_ENCRYPTION_KEY.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            // Сообщение исключения Base64 цитирует недопустимый символ — то есть кусок ключа
            throw new InvalidKeyException("TOKEN_ENCRYPTION_KEY — не base64.");
        }
        if (bytes.length != KEY_BYTES) {
            int length = bytes.length;
            Arrays.fill(bytes, (byte) 0);
            throw new InvalidKeyException("TOKEN_ENCRYPTION_KEY декодируется в " + length
                    + " байт, а для AES-256 нужно ровно " + KEY_BYTES + ".");
        }
        return bytes;
    }

    /**
     * Неверный или отсутствующий ключ. Наследник {@link FailureAnalyzedException}: Spring Boot
     * печатает его как «APPLICATION FAILED TO START» с описанием и подсказкой, без
     * многоэкранного стектрейса. Сам ключ в сообщение не попадает никогда.
     */
    public static class InvalidKeyException extends FailureAnalyzedException {
        public InvalidKeyException(String description) {
            super(description, HOWTO);
        }
    }

    /** Значение из базы не расшифровывается. Сообщение — только причина, без самого значения. */
    public static class UnreadableTokenException extends RuntimeException {
        public UnreadableTokenException(String reason) {
            super(reason);
        }
    }
}
