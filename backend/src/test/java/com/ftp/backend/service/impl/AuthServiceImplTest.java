package com.ftp.backend.service.impl;

import com.ftp.backend.dto.request.LoginRequest;
import com.ftp.backend.dto.request.RegisterRequest;
import com.ftp.backend.dto.response.AuthResponse;
import com.ftp.backend.entity.ERole;
import com.ftp.backend.entity.Role;
import com.ftp.backend.entity.User;
import com.ftp.backend.entity.Wallet;
import com.ftp.backend.exception.ConflictException;
import com.ftp.backend.exception.NotFoundException;
import com.ftp.backend.repository.RoleRepository;
import com.ftp.backend.repository.UserRepository;
import com.ftp.backend.repository.WalletRepository;
import com.ftp.backend.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for registration and login. Collaborators (repositories, encoder,
 * token provider, authentication manager) are mocked; the JWT provider is only
 * asked to return a stub token, so no cryptography runs here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthServiceImpl unit tests")
class AuthServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private WalletRepository walletRepository;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(userRepository, roleRepository, passwordEncoder,
                jwtTokenProvider, authenticationManager, walletRepository);
        ReflectionTestUtils.setField(authService, "welcomeBalance", new BigDecimal("10000.00"));
    }

    private RegisterRequest registerRequest(String username, String email) {
        return RegisterRequest.builder()
                .username(username).email(email).password("secret123")
                .firstName("New").lastName("Bie").build();
    }

    private Role userRole() {
        Role role = new Role();
        role.setName(ERole.ROLE_USER);
        return role;
    }

    @Test
    void registerCreatesUserAssignsRoleAndFundsWallet() {
        when(userRepository.existsByUsername("newbie")).thenReturn(false);
        when(userRepository.existsByEmail("newbie@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("hashed");
        when(roleRepository.findByName(ERole.ROLE_USER)).thenReturn(userRole());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User saved = inv.getArgument(0);
            saved.setId(42L);
            return saved;
        });
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtTokenProvider.generateToken(anyString(), anyList())).thenReturn("stub-token");

        AuthResponse response = authService.register(registerRequest("newbie", "newbie@example.com"));

        assertThat(response.getToken()).isEqualTo("stub-token");
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getUserId()).isEqualTo(42L);
        assertThat(response.getUsername()).isEqualTo("newbie");
        assertThat(response.getRoles()).containsExactly("ROLE_USER");

        ArgumentCaptor<Wallet> walletCaptor = ArgumentCaptor.forClass(Wallet.class);
        verify(walletRepository).save(walletCaptor.capture());
        Wallet wallet = walletCaptor.getValue();
        assertThat(wallet.getUserId()).isEqualTo(42L);
        assertThat(wallet.getBalance()).isEqualByComparingTo("10000.00");
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("10000.00");
        assertThat(wallet.getCurrency()).isEqualTo("USD");
    }

    @Test
    void registerHashesPasswordBeforePersisting() {
        when(userRepository.existsByUsername("newbie")).thenReturn(false);
        when(userRepository.existsByEmail("newbie@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("hashed");
        when(roleRepository.findByName(ERole.ROLE_USER)).thenReturn(userRole());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtTokenProvider.generateToken(anyString(), anyList())).thenReturn("t");

        authService.register(registerRequest("newbie", "newbie@example.com"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("hashed");
    }

    @Test
    void registerRejectsDuplicateUsername() {
        when(userRepository.existsByUsername("dupe")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(registerRequest("dupe", "dupe@example.com")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Username is already taken");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByUsername("dupe")).thenReturn(false);
        when(userRepository.existsByEmail("dupe@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(registerRequest("dupe", "dupe@example.com")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Email is already in use");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void registerFailsWhenDefaultRoleIsNotInitialized() {
        when(userRepository.existsByUsername("newbie")).thenReturn(false);
        when(userRepository.existsByEmail("newbie@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("hashed");
        when(roleRepository.findByName(ERole.ROLE_USER)).thenReturn(null);

        assertThatThrownBy(() -> authService.register(registerRequest("newbie", "newbie@example.com")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("ROLE_USER is not initialized");
    }

    // ---- Login ---------------------------------------------------------

    @Test
    void loginResolvesUserAndIssuesToken() {
        User user = User.builder().id(5L).username("trader").email("trader@example.com")
                .password("hashed").firstName("Tra").lastName("Der")
                .roles(Set.of(userRole())).build();
        UserDetails principal = new org.springframework.security.core.userdetails.User(
                "trader", "hashed", List.of(new SimpleGrantedAuthority("ROLE_USER")));

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(userRepository.findByUsername("trader")).thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateToken(anyString(), anyList())).thenReturn("login-token");

        AuthResponse response = authService.login(LoginRequest.builder()
                .usernameOrEmail("trader").password("secret123").build());

        assertThat(response.getToken()).isEqualTo("login-token");
        assertThat(response.getUserId()).isEqualTo(5L);
        assertThat(response.getUsername()).isEqualTo("trader");
        assertThat(response.getRoles()).containsExactly("ROLE_USER");
    }

    @Test
    void loginFailsWhenUserDisappearsAfterAuthentication() {
        UserDetails principal = new org.springframework.security.core.userdetails.User(
                "ghost", "hashed", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(LoginRequest.builder()
                .usernameOrEmail("ghost").password("secret123").build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("User not found");

        verify(jwtTokenProvider, never()).generateToken(anyString(), anyList());
    }
}
