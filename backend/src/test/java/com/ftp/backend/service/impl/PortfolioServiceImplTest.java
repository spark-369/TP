package com.ftp.backend.service.impl;

import com.ftp.backend.dto.response.PortfolioResponse;
import com.ftp.backend.dto.response.PortfolioSummary;
import com.ftp.backend.entity.Portfolio;
import com.ftp.backend.entity.User;
import com.ftp.backend.repository.PortfolioRepository;
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
 * Unit tests for portfolio aggregation: mapping holdings and computing the
 * summary totals / profit-and-loss figures.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PortfolioServiceImpl unit tests")
class PortfolioServiceImplTest {

    private static final Long USER_ID = 3L;

    @Mock
    private PortfolioRepository portfolioRepository;

    private PortfolioServiceImpl portfolioService;

    @BeforeEach
    void setUp() {
        portfolioService = new PortfolioServiceImpl(portfolioRepository);
    }

    private Portfolio holding(String symbol, String quantity, String avgBuyPrice, String currentValue) {
        return Portfolio.builder().id(1L).user(User.builder().id(USER_ID).build())
                .symbol(symbol)
                .quantity(new BigDecimal(quantity))
                .avgBuyPrice(new BigDecimal(avgBuyPrice))
                .currentValue(new BigDecimal(currentValue))
                .build();
    }

    @Test
    void getPortfolioMapsEveryHolding() {
        when(portfolioRepository.findByUserId(USER_ID)).thenReturn(List.of(
                holding("AAPL", "2", "150.00", "400.00"),
                holding("MSFT", "1", "300.00", "310.00")));

        List<PortfolioResponse> responses = portfolioService.getPortfolio(USER_ID);

        assertThat(responses).hasSize(2);
        assertThat(responses).extracting(PortfolioResponse::getSymbol).containsExactly("AAPL", "MSFT");
        assertThat(responses.get(0).getUserId()).isEqualTo(USER_ID);
        assertThat(responses.get(0).getQuantity()).isEqualByComparingTo("2");
    }

    @Test
    void getPortfolioSummaryAggregatesValueCostAndProfit() {
        when(portfolioRepository.findByUserId(USER_ID)).thenReturn(List.of(
                holding("AAPL", "2", "150.00", "400.00"),
                holding("MSFT", "1", "300.00", "310.00")));

        PortfolioSummary summary = portfolioService.getPortfolioSummary(USER_ID);

        // currentValue: 400 + 310 = 710 ; cost: 2*150 + 1*300 = 600 ; P/L = 110
        assertThat(summary.getTotalValue()).isEqualByComparingTo("710.00");
        assertThat(summary.getTotalCost()).isEqualByComparingTo("600.00");
        assertThat(summary.getTotalProfitLoss()).isEqualByComparingTo("110.00");
        // 110 / 600 * 100 = 18.33
        assertThat(summary.getTotalProfitLossPercent()).isEqualByComparingTo("18.33");
        assertThat(summary.getHoldings()).hasSize(2);
    }

    @Test
    void summaryWithNoHoldingsIsAllZero() {
        when(portfolioRepository.findByUserId(USER_ID)).thenReturn(List.of());

        PortfolioSummary summary = portfolioService.getPortfolioSummary(USER_ID);

        assertThat(summary.getTotalValue()).isEqualByComparingTo("0");
        assertThat(summary.getTotalCost()).isEqualByComparingTo("0");
        assertThat(summary.getTotalProfitLoss()).isEqualByComparingTo("0");
        assertThat(summary.getTotalProfitLossPercent()).isEqualByComparingTo("0");
        assertThat(summary.getHoldings()).isEmpty();
    }

    @Test
    void getAllPortfoliosMapsRepositoryResults() {
        when(portfolioRepository.findAll()).thenReturn(List.of(holding("AAPL", "1", "100.00", "100.00")));

        List<PortfolioResponse> responses = portfolioService.getAllPortfolios();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getSymbol()).isEqualTo("AAPL");
    }

    @Test
    void getPortfolioSummaryByUserIdAggregatesForTargetUser() {
        when(portfolioRepository.findByUserId(USER_ID)).thenReturn(List.of(
                holding("AAPL", "2", "150.00", "400.00")));

        PortfolioSummary summary = portfolioService.getPortfolioSummaryByUserId(USER_ID);

        assertThat(summary.getTotalValue()).isEqualByComparingTo("400.00");
        assertThat(summary.getTotalCost()).isEqualByComparingTo("300.00");
        assertThat(summary.getTotalProfitLoss()).isEqualByComparingTo("100.00");
    }
}
