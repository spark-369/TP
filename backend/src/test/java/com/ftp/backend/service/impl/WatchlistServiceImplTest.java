package com.ftp.backend.service.impl;

import com.ftp.backend.dto.request.WatchlistRequest;
import com.ftp.backend.dto.response.WatchlistResponse;
import com.ftp.backend.entity.User;
import com.ftp.backend.entity.Watchlist;
import com.ftp.backend.entity.WatchlistItem;
import com.ftp.backend.exception.ConflictException;
import com.ftp.backend.exception.NotFoundException;
import com.ftp.backend.repository.WatchlistItemRepository;
import com.ftp.backend.repository.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for watchlist management: creation, item add/remove with the
 * duplicate-symbol guard, and ownership-scoped lookups.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WatchlistServiceImpl unit tests")
class WatchlistServiceImplTest {

    private static final Long USER_ID = 8L;
    private static final Long WATCHLIST_ID = 20L;

    @Mock
    private WatchlistRepository watchlistRepository;
    @Mock
    private WatchlistItemRepository watchlistItemRepository;

    private WatchlistServiceImpl watchlistService;

    @BeforeEach
    void setUp() {
        watchlistService = new WatchlistServiceImpl(watchlistRepository, watchlistItemRepository);
    }

    private Watchlist watchlist(Long id, Long ownerId) {
        return Watchlist.builder().id(id).user(User.builder().id(ownerId).build()).name("Tech").build();
    }

    private WatchlistItem item(Long watchlistId, String symbol) {
        return WatchlistItem.builder().id(1L).watchlist(watchlist(watchlistId, USER_ID))
                .symbol(symbol).companyName("Apple Inc.").build();
    }

    @Test
    void createWatchlistPersistsAndReturnsResponse() {
        when(watchlistRepository.save(any(Watchlist.class))).thenAnswer(inv -> {
            Watchlist saved = inv.getArgument(0);
            saved.setId(WATCHLIST_ID);
            return saved;
        });
        when(watchlistItemRepository.findByWatchlistId(WATCHLIST_ID)).thenReturn(List.of());

        WatchlistResponse response = watchlistService.createWatchlist(USER_ID,
                WatchlistRequest.builder().name("Tech").build());

        assertThat(response.getId()).isEqualTo(WATCHLIST_ID);
        assertThat(response.getName()).isEqualTo("Tech");
        assertThat(response.getUserId()).isEqualTo(USER_ID);
        assertThat(response.getItems()).isEmpty();
    }

    @Test
    void getWatchlistsMapsItemsForEachList() {
        when(watchlistRepository.findByUserId(USER_ID)).thenReturn(List.of(watchlist(WATCHLIST_ID, USER_ID)));
        when(watchlistItemRepository.findByWatchlistId(WATCHLIST_ID)).thenReturn(List.of(item(WATCHLIST_ID, "AAPL")));

        List<WatchlistResponse> responses = watchlistService.getWatchlists(USER_ID);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getItems()).hasSize(1);
        assertThat(responses.get(0).getItems().get(0).getSymbol()).isEqualTo("AAPL");
    }

    @Test
    void addItemPersistsWhenSymbolIsNew() {
        Watchlist wl = watchlist(WATCHLIST_ID, USER_ID);
        when(watchlistRepository.findByIdAndUserId(WATCHLIST_ID, USER_ID)).thenReturn(wl);
        when(watchlistItemRepository.findByWatchlistIdAndSymbol(WATCHLIST_ID, "AAPL")).thenReturn(Optional.empty());
        when(watchlistItemRepository.save(any(WatchlistItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(watchlistItemRepository.findByWatchlistId(WATCHLIST_ID)).thenReturn(List.of(item(WATCHLIST_ID, "AAPL")));

        WatchlistResponse response = watchlistService.addItem(USER_ID, WATCHLIST_ID, "AAPL", "Apple Inc.");

        assertThat(response.getItems()).hasSize(1);
        ArgumentCaptor<WatchlistItem> captor = ArgumentCaptor.forClass(WatchlistItem.class);
        verify(watchlistItemRepository).save(captor.capture());
        assertThat(captor.getValue().getSymbol()).isEqualTo("AAPL");
    }

    @Test
    void addItemRejectsDuplicateSymbol() {
        when(watchlistRepository.findByIdAndUserId(WATCHLIST_ID, USER_ID)).thenReturn(watchlist(WATCHLIST_ID, USER_ID));
        when(watchlistItemRepository.findByWatchlistIdAndSymbol(WATCHLIST_ID, "AAPL"))
                .thenReturn(Optional.of(item(WATCHLIST_ID, "AAPL")));

        assertThatThrownBy(() -> watchlistService.addItem(USER_ID, WATCHLIST_ID, "AAPL", "Apple Inc."))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Symbol already in watchlist");

        verify(watchlistItemRepository, never()).save(any(WatchlistItem.class));
    }

    @Test
    void addItemRejectsMissingWatchlist() {
        when(watchlistRepository.findByIdAndUserId(WATCHLIST_ID, USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> watchlistService.addItem(USER_ID, WATCHLIST_ID, "AAPL", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Watchlist not found");
    }

    @Test
    void removeItemDeletesWhenPresent() {
        when(watchlistRepository.findByIdAndUserId(WATCHLIST_ID, USER_ID)).thenReturn(watchlist(WATCHLIST_ID, USER_ID));
        WatchlistItem existing = item(WATCHLIST_ID, "AAPL");
        when(watchlistItemRepository.findByWatchlistIdAndSymbol(WATCHLIST_ID, "AAPL")).thenReturn(Optional.of(existing));
        when(watchlistItemRepository.findByWatchlistId(WATCHLIST_ID)).thenReturn(List.of());

        watchlistService.removeItem(USER_ID, WATCHLIST_ID, "AAPL");

        verify(watchlistItemRepository).delete(existing);
    }

    @Test
    void deleteWatchlistRemovesItemsThenList() {
        Watchlist wl = watchlist(WATCHLIST_ID, USER_ID);
        when(watchlistRepository.findByIdAndUserId(WATCHLIST_ID, USER_ID)).thenReturn(wl);
        when(watchlistItemRepository.findByWatchlistId(WATCHLIST_ID)).thenReturn(List.of(item(WATCHLIST_ID, "AAPL")));

        watchlistService.deleteWatchlist(USER_ID, WATCHLIST_ID);

        verify(watchlistItemRepository).delete(any(WatchlistItem.class));
        verify(watchlistRepository).delete(wl);
    }

    @Test
    void getAllWatchlistsMapsRepositoryResults() {
        when(watchlistRepository.findAll()).thenReturn(List.of(watchlist(WATCHLIST_ID, USER_ID)));
        when(watchlistItemRepository.findByWatchlistId(WATCHLIST_ID)).thenReturn(List.of());

        List<WatchlistResponse> responses = watchlistService.getAllWatchlists();

        assertThat(responses).hasSize(1);
    }

    @Test
    void getWatchlistsByUserIdDelegatesToRepository() {
        when(watchlistRepository.findByUserId(99L)).thenReturn(List.of(watchlist(WATCHLIST_ID, 99L)));
        when(watchlistItemRepository.findByWatchlistId(WATCHLIST_ID)).thenReturn(List.of());

        List<WatchlistResponse> responses = watchlistService.getWatchlistsByUserId(99L);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getUserId()).isEqualTo(99L);
    }
}
