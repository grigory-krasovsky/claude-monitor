package com.example.claudeusagemonitor.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.claudeusagemonitor.account.AccountRepository;
import com.example.claudeusagemonitor.account.AccountSession;
import com.example.claudeusagemonitor.config.MonitorProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** Сохранение ротированных токенов: сбой базы не должен терять refresh token до следующего обмена. */
class TokenProviderTests {

    @Test
    @DisplayName("не записанные в базу токены дописываются при следующем обращении, без нового обмена")
    void retriesPersistAfterDatabaseFailure() {
        FlakyRepository repository = new FlakyRepository();
        List<String> exchanged = new ArrayList<>();
        TokenProvider provider = new TokenProvider(new MonitorProperties(), null, null, repository) {
            @Override
            public TokenGrant exchange(String refreshToken) {
                exchanged.add(refreshToken);
                return new TokenGrant("access-1", "refresh-1", Instant.now().plusSeconds(8 * 3600));
            }
        };
        AccountSession session = new AccountSession(1, 42,
                new AccountSession.Tokens("access-0", "refresh-0", Instant.now().minusSeconds(1)),
                new AccountSession.Board(null, null, null));

        repository.failNext = true;
        assertThat(provider.accessToken(session)).isEqualTo("access-1");
        assertThat(repository.saved).isEmpty();
        assertThat(session.tokens().isUnsaved()).isTrue();

        assertThat(provider.accessToken(session)).isEqualTo("access-1");
        assertThat(repository.saved).containsExactly("refresh-1");
        assertThat(session.tokens().isUnsaved()).isFalse();
        assertThat(exchanged).containsExactly("refresh-0");

        provider.accessToken(session);
        assertThat(repository.saved).hasSize(1);
    }

    private static final class FlakyRepository extends AccountRepository {
        final List<String> saved = new ArrayList<>();
        boolean failNext;

        FlakyRepository() {
            super(null, null);
        }

        @Override
        public void saveTokens(long id, long chatId, String accessToken, String refreshToken, Instant expiresAt) {
            if (failNext) {
                failNext = false;
                throw new DataAccessResourceFailureException("база недоступна");
            }
            saved.add(refreshToken);
        }
    }
}
