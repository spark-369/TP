package com.ftp.backend.security;

import com.ftp.backend.entity.User;
import com.ftp.backend.exception.ForbiddenActionException;
import com.ftp.backend.exception.NotFoundException;
import com.ftp.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CurrentUserUtils}: resolving the caller from the
 * SecurityContext, mapping to the database id, and the admin check.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CurrentUserUtils unit tests")
class CurrentUserUtilsTest {

    @Mock
    private UserRepository userRepository;

    private CurrentUserUtils currentUserUtils;

    @BeforeEach
    void setUp() {
        currentUserUtils = new CurrentUserUtils(userRepository);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username, String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, authorities));
    }

    @Test
    void getCurrentUserIdResolvesFromUsername() {
        authenticateAs("trader", "ROLE_USER");
        when(userRepository.findByUsername("trader")).thenReturn(Optional.of(User.builder().id(77L).build()));

        assertThat(currentUserUtils.getCurrentUserId()).isEqualTo(77L);
        assertThat(currentUserUtils.getCurrentUsername()).isEqualTo("trader");
    }

    @Test
    void getCurrentUserIdThrowsWhenUserRowIsGone() {
        authenticateAs("ghost", "ROLE_USER");
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> currentUserUtils.getCurrentUserId())
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("User not found: ghost");
    }

    @Test
    void getCurrentUsernameThrowsWhenUnauthenticated() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> currentUserUtils.getCurrentUsername())
                .isInstanceOf(ForbiddenActionException.class)
                .hasMessageContaining("User not authenticated");
    }

    @Test
    void getCurrentUsernameThrowsForAnonymousUser() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatThrownBy(() -> currentUserUtils.getCurrentUsername())
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void isAdminTrueOnlyForAdminRole() {
        authenticateAs("admin", "ROLE_ADMIN");
        assertThat(currentUserUtils.isAdmin()).isTrue();

        authenticateAs("trader", "ROLE_USER");
        assertThat(currentUserUtils.isAdmin()).isFalse();
    }

    @Test
    void isAdminFalseWhenUnauthenticated() {
        SecurityContextHolder.clearContext();
        assertThat(currentUserUtils.isAdmin()).isFalse();
    }
}
