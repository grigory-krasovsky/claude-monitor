package com.example.claudeusagemonitor.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.claudeusagemonitor.testsupport.TestBeans;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** AES-256-GCM для токенов в базе: формат, случайный IV, проверка целостности и AAD, проверка ключа. */
class TokenCipherTests {

    private static final String TOKEN = "sk-ant-ort01-secret-value";
    private static final long CHAT = 4242;

    private final TokenCipher cipher = new TokenCipher(TestBeans.TOKEN_KEY);

    @Test
    @DisplayName("шифрование и расшифровка возвращают исходный токен, в шифротексте его нет")
    void roundTrip() {
        String stored = cipher.encrypt(TOKEN, CHAT);

        assertThat(stored).startsWith("v1:").doesNotContain(TOKEN).doesNotContain("secret");
        assertThat(cipher.decrypt(stored, CHAT)).isEqualTo(TOKEN);
        // IV 12 байт + тег 16 байт поверх длины открытого текста
        assertThat(Base64.getDecoder().decode(stored.substring(3))).hasSize(12 + TOKEN.length() + 16);
    }

    @Test
    @DisplayName("два шифрования одного значения дают разные IV и разный шифротекст")
    void freshIvEveryTime() {
        String first = cipher.encrypt(TOKEN, CHAT);
        String second = cipher.encrypt(TOKEN, CHAT);

        assertThat(first).isNotEqualTo(second);
        byte[] a = Base64.getDecoder().decode(first.substring(3));
        byte[] b = Base64.getDecoder().decode(second.substring(3));
        assertThat(java.util.Arrays.copyOf(a, 12)).isNotEqualTo(java.util.Arrays.copyOf(b, 12));
        assertThat(cipher.decrypt(first, CHAT)).isEqualTo(cipher.decrypt(second, CHAT));
    }

    @Test
    @DisplayName("подмена любого байта — ошибка расшифровки, в сообщении нет значения")
    void tamperingIsDetected() {
        String stored = cipher.encrypt(TOKEN, CHAT);
        byte[] raw = Base64.getDecoder().decode(stored.substring(3));
        for (int i = 0; i < raw.length; i++) {
            byte[] tampered = raw.clone();
            tampered[i] ^= 0x01;
            String forged = "v1:" + Base64.getEncoder().encodeToString(tampered);
            assertThatThrownBy(() -> cipher.decrypt(forged, CHAT))
                    .isInstanceOf(TokenCipher.UnreadableTokenException.class)
                    .hasMessageNotContaining(forged.substring(3));
        }
    }

    @Test
    @DisplayName("шифротекст чужого chat_id не расшифровывается")
    void foreignChatIdIsRejected() {
        String stored = cipher.encrypt(TOKEN, CHAT);

        assertThatThrownBy(() -> cipher.decrypt(stored, CHAT + 1))
                .isInstanceOf(TokenCipher.UnreadableTokenException.class);
        assertThatThrownBy(() -> cipher.decrypt(stored, -CHAT))
                .isInstanceOf(TokenCipher.UnreadableTokenException.class);
    }

    @Test
    @DisplayName("значение, зашифрованное другим ключом, не расшифровывается")
    void otherKeyIsRejected() {
        String stored = new TokenCipher(randomKey()).encrypt(TOKEN, CHAT);

        assertThatThrownBy(() -> cipher.decrypt(stored, CHAT))
                .isInstanceOf(TokenCipher.UnreadableTokenException.class);
    }

    @Test
    @DisplayName("строка без префикса v1: и мусор после префикса — ошибка без значения в сообщении")
    void malformedValues() {
        assertThatThrownBy(() -> cipher.decrypt(TOKEN, CHAT))
                .isInstanceOf(TokenCipher.UnreadableTokenException.class)
                .hasMessageNotContaining(TOKEN);
        assertThatThrownBy(() -> cipher.decrypt("v2:" + cipher.encrypt(TOKEN, CHAT).substring(3), CHAT))
                .isInstanceOf(TokenCipher.UnreadableTokenException.class);
        assertThatThrownBy(() -> cipher.decrypt("v1:не-base64!", CHAT))
                .isInstanceOf(TokenCipher.UnreadableTokenException.class);
        assertThatThrownBy(() -> cipher.decrypt("v1:AAAA", CHAT))
                .isInstanceOf(TokenCipher.UnreadableTokenException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("пустое значение не шифруется и хранится как null")
    void blankIsNull(String value) {
        assertThat(cipher.encrypt(value, CHAT)).isNull();
        assertThat(cipher.decrypt(value, CHAT)).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("ключ не задан — ошибка конфигурации")
    void missingKey(String key) {
        assertThatThrownBy(() -> new TokenCipher(key))
                .isInstanceOf(TokenCipher.InvalidKeyException.class)
                .hasMessageContaining("TOKEN_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("ключ не base64 — ошибка конфигурации, сам ключ в сообщение не попадает")
    void notBase64Key() {
        String key = "это-не-base64-ключ-%%%";
        assertThatThrownBy(() -> new TokenCipher(key))
                .isInstanceOf(TokenCipher.InvalidKeyException.class)
                .hasMessageContaining("base64")
                .hasMessageNotContaining(key)
                .hasMessageNotContaining("%");
    }

    @Test
    @DisplayName("ключ не 32 байта — ошибка конфигурации с длиной, но без ключа")
    void wrongLengthKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        String longKey = Base64.getEncoder().encodeToString(new byte[33]);

        assertThatThrownBy(() -> new TokenCipher(shortKey))
                .isInstanceOf(TokenCipher.InvalidKeyException.class)
                .hasMessageContaining("16 байт")
                .hasMessageNotContaining(shortKey);
        assertThatThrownBy(() -> new TokenCipher(longKey))
                .isInstanceOf(TokenCipher.InvalidKeyException.class)
                .hasMessageContaining("33 байт");
    }

    @Test
    @DisplayName("toString не раскрывает ключ")
    void toStringHidesKey() {
        assertThat(cipher.toString()).doesNotContain(TestBeans.TOKEN_KEY);
    }

    static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
