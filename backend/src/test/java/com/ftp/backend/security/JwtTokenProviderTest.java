package com.ftp.backend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the JWT provider. A real HMAC key is used so token
 * round-tripping, tamper detection and misconfiguration guards are all covered
 * without a Spring context.
 */
@DisplayName("JwtTokenProvider unit tests")
class JwtTokenProviderTest {

    // Base64 value decoding to 40 bytes - long enough for HMAC-SHA256.
    private static final String SECRET = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, 3_600_000L);
    }

    @Test
    void generateThenParseRoundTripsSubjectAndRoles() {
        String token = provider.generateToken("trader",
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUsernameFromToken(token)).isEqualTo("trader");
        assertThat(provider.getRolesFromToken(token)).containsExactly("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void validateTokenRejectsGarbage() {
        assertThat(provider.validateToken("not-a-jwt")).isFalse();
    }

    @Test
    void validateTokenRejectsTokenSignedWithAnotherKey() {
        JwtTokenProvider other = new JwtTokenProvider(
                "3132333435363738393031323334353637383930313233343536373839303132", 3_600_000L);
        String foreignToken = other.generateToken("intruder", List.of(new SimpleGrantedAuthority("ROLE_USER")));

        assertThat(provider.validateToken(foreignToken)).isFalse();
    }

    @Test
    void expiredTokenIsRejected() {
        JwtTokenProvider shortLived = new JwtTokenProvider(SECRET, -1_000L);
        String expired = shortLived.generateToken("trader", List.of(new SimpleGrantedAuthority("ROLE_USER")));

        assertThat(provider.validateToken(expired)).isFalse();
    }

    @Test
    void constructorRejectsBlankSecret() {
        assertThatThrownBy(() -> new JwtTokenProvider("  ", 1_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret is not configured");
    }

    @Test
    void constructorRejectsNonBase64Secret() {
        // "!" is not a base64 character, so decoding fails and the provider
        // refuses to start with a malformed secret.
        assertThatThrownBy(() -> new JwtTokenProvider("not base64 !!!", 1_000L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructorRejectsTooShortKey() {
        // "c2hvcnQ=" decodes to "short" (5 bytes).
        assertThatThrownBy(() -> new JwtTokenProvider("c2hvcnQ=", 1_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }
}
