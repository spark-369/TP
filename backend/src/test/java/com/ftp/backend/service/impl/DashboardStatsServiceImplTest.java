package com.ftp.backend.service.impl;

import com.ftp.backend.dto.response.DashboardStatsResponse;
import com.ftp.backend.entity.Order;
import com.ftp.backend.entity.Wallet;
import com.ftp.backend.repository.MarketDataRepository;
import com.ftp.backend.repository.OrderRepository;
import com.ftp.backend.repository.PortfolioRepository;
import com.ftp.backend.repository.TransactionRepository;
import com.ftp.backend.repository.UserRepository;
import com.ftp.backend.repository.WalletRepository;
import com.ftp.backend.repository.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the admin dashboard aggregation: entity counts plus the summed
 * wallet balance and trading volume.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardStatsServiceImpl unit tests")
class DashboardStatsServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private MarketDataRepository marketDataRepository;
    @Mock private WatchlistRepository watchlistRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private WalletRepository walletRepository;

    private DashboardStatsServiceImpl dashboardStatsService;

    @BeforeEach
    void setUp() {
        dashboardStatsService = new DashboardStatsServiceImpl(userRepository, orderRepository,
                transactionRepository, marketDataRepository, watchlistRepository, portfolioRepository, walletRepository);
    }

    @Test
    void getStatsAggregatesCountsBalancesAndVolume() {
        when(userRepository.count()).thenReturn(3L);
        when(orderRepository.count()).thenReturn(5L);
        when(transactionRepository.count()).thenReturn(5L);
        when(marketDataRepository.count()).thenReturn(10L);
        when(watchlistRepository.count()).thenReturn(2L);
        when(portfolioRepository.count()).thenReturn(4L);

        when(walletRepository.findAll()).thenReturn(List.of(
                Wallet.builder().balance(new BigDecimal("100.00")).build(),
                Wallet.builder().balance(new BigDecimal("250.50")).build()));
        when(orderRepository.findAll()).thenReturn(List.of(
                Order.builder().totalAmount(new BigDecimal("200.00")).build(),
                Order.builder().totalAmount(new BigDecimal("300.00")).build()));

        DashboardStatsResponse stats = dashboardStatsService.getStats();

        assertThat(stats.getTotalUsers()).isEqualTo(3L);
        assertThat(stats.getTotalOrders()).isEqualTo(5L);
        assertThat(stats.getTotalTransactions()).isEqualTo(5L);
        assertThat(stats.getTotalMarketData()).isEqualTo(10L);
        assertThat(stats.getTotalWatchlists()).isEqualTo(2L);
        assertThat(stats.getTotalPortfolios()).isEqualTo(4L);
        assertThat(stats.getTotalWalletBalance()).isEqualByComparingTo("350.50");
        assertThat(stats.getTotalTradingVolume()).isEqualByComparingTo("500.00");
    }

    @Test
    void getStatsToleratesNullBalancesAndAmounts() {
        when(walletRepository.findAll()).thenReturn(List.of(
                Wallet.builder().balance(null).build(),
                Wallet.builder().balance(new BigDecimal("10.00")).build()));
        when(orderRepository.findAll()).thenReturn(List.of(
                Order.builder().totalAmount(null).build()));

        DashboardStatsResponse stats = dashboardStatsService.getStats();

        assertThat(stats.getTotalWalletBalance()).isEqualByComparingTo("10.00");
        assertThat(stats.getTotalTradingVolume()).isEqualByComparingTo("0.00");
    }
}
