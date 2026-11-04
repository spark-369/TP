package com.ftp.backend.service.impl;

import com.ftp.backend.dto.request.UpdateUserRequest;
import com.ftp.backend.dto.response.UserResponse;
import com.ftp.backend.entity.ERole;
import com.ftp.backend.entity.Role;
import com.ftp.backend.entity.User;
import com.ftp.backend.entity.Watchlist;
import com.ftp.backend.exception.ConflictException;
import com.ftp.backend.exception.NotFoundException;
import com.ftp.backend.repository.OrderRepository;
import com.ftp.backend.repository.PortfolioRepository;
import com.ftp.backend.repository.TransactionRepository;
import com.ftp.backend.repository.UserRepository;
import com.ftp.backend.repository.WalletRepository;
import com.ftp.backend.repository.WatchlistItemRepository;
import com.ftp.backend.repository.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for user management: {@link UserDetails} resolution, profile reads
 * and updates, and the cascade-aware delete.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserServiceImpl unit tests")
class UserServiceImplTest {

    private static final Long USER_ID = 11L;

    @Mock private UserRepository userRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private WatchlistRepository watchlistRepository;
    @Mock private WatchlistItemRepository watchlistItemRepository;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userRepository, walletRepository, orderRepository,
                transactionRepository, portfolioRepository, watchlistRepository, watchlistItemRepository);
    }

    private Role role(ERole name) {
        Role role = new Role();
        role.setName(name);
        return role;
    }

    private User user(Long id, String username) {
        return User.builder().id(id).username(username).email(username + "@example.com")
                .password("hashed").firstName("Tra").lastName("Der")
                .enabled(true).roles(Set.of(role(ERole.ROLE_USER))).build();
    }

    // ---- loadUserByUsername --------------------------------------------

    @Test
    void loadUserByUsernameReturnsDetailsWithAuthorities() {
        when(userRepository.findByUsername("trader")).thenReturn(Optional.of(user(USER_ID, "trader")));

        UserDetails details = userService.loadUserByUsername("trader");

        assertThat(details.getUsername()).isEqualTo("trader");
        assertThat(details.getPassword()).isEqualTo("hashed");
        assertThat(details.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    @Test
    void loadUserByUsernameFallsBackToEmail() {
        when(userRepository.findByUsername("trader@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("trader@example.com")).thenReturn(Optional.of(user(USER_ID, "trader")));

        UserDetails details = userService.loadUserByUsername("trader@example.com");

        assertThat(details.getUsername()).isEqualTo("trader");
    }

    @Test
    void loadUserByUsernameThrowsWhenMissing() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("User not found: ghost");
    }

    // ---- reads ---------------------------------------------------------

    @Test
    void getCurrentUserMapsEntity() {
        when(userRepository.findByUsername("trader")).thenReturn(Optional.of(user(USER_ID, "trader")));

        UserResponse response = userService.getCurrentUser("trader");

        assertThat(response.getId()).isEqualTo(USER_ID);
        assertThat(response.getUsername()).isEqualTo("trader");
        assertThat(response.getRoles()).containsExactly("ROLE_USER");
        assertThat(response.getEnabled()).isTrue();
    }

    @Test
    void getCurrentUserThrowsWhenMissing() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getCurrentUser("ghost"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void getUserByIdMapsEntity() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, "trader")));

        UserResponse response = userService.getUserById(USER_ID);

        assertThat(response.getId()).isEqualTo(USER_ID);
    }

    @Test
    void getAllUsersMapsEveryRow() {
        when(userRepository.findAll()).thenReturn(List.of(user(1L, "a"), user(2L, "b")));

        List<UserResponse> responses = userService.getAllUsers();

        assertThat(responses).hasSize(2);
    }

    // ---- update --------------------------------------------------------

    @Test
    void updateUserChangesNamesAndEmail() {
        when(userRepository.findByUsername("trader")).thenReturn(Optional.of(user(USER_ID, "trader")));
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponse response = userService.updateUser("trader",
                UpdateUserRequest.builder().firstName("New").lastName("Name").email("new@example.com").build());

        assertThat(response.getFirstName()).isEqualTo("New");
        assertThat(response.getLastName()).isEqualTo("Name");
        assertThat(response.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void updateUserRejectsEmailAlreadyInUse() {
        when(userRepository.findByUsername("trader")).thenReturn(Optional.of(user(USER_ID, "trader")));
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.updateUser("trader",
                UpdateUserRequest.builder().firstName("New").lastName("Name").email("taken@example.com").build()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Email is already in use");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void updateUserKeepsEmailWhenBlank() {
        User existing = user(USER_ID, "trader");
        when(userRepository.findByUsername("trader")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponse response = userService.updateUser("trader",
                UpdateUserRequest.builder().firstName("New").lastName("Name").email(" ").build());

        assertThat(response.getEmail()).isEqualTo("trader@example.com");
    }

    // ---- delete --------------------------------------------------------

    @Test
    void deleteUserRemovesDependentRowsThenUser() {
        when(userRepository.existsById(USER_ID)).thenReturn(true);
        when(watchlistRepository.findByUserId(USER_ID))
                .thenReturn(List.of(Watchlist.builder().id(20L).name("Tech").build()));

        userService.deleteUser(USER_ID);

        verify(walletRepository).deleteByUserId(USER_ID);
        verify(orderRepository).deleteByUserId(USER_ID);
        verify(transactionRepository).deleteByUserId(USER_ID);
        verify(portfolioRepository).deleteByUserId(USER_ID);
        verify(watchlistItemRepository).deleteByWatchlistId(20L);
        verify(watchlistRepository).delete(any(Watchlist.class));
        verify(userRepository).deleteById(USER_ID);
    }

    @Test
    void deleteUserRejectsMissingUser() {
        when(userRepository.existsById(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> userService.deleteUser(USER_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("User not found");

        verify(userRepository, never()).deleteById(any());
    }
}
