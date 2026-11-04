package com.ftp.backend.service.impl;

import com.ftp.backend.dto.response.MarketDataResponse;
import com.ftp.backend.entity.MarketData;
import com.ftp.backend.exception.NotFoundException;
import com.ftp.backend.repository.MarketDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the market data service: read mapping, search delegation and
 * the create / update / delete paths.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarketDataServiceImpl unit tests")
class MarketDataServiceImplTest {

    @Mock
    private MarketDataRepository marketDataRepository;

    private MarketDataServiceImpl marketDataService;

    @BeforeEach
    void setUp() {
        marketDataService = new MarketDataServiceImpl(marketDataRepository);
    }

    private MarketData market(String symbol, String price) {
        return MarketData.builder().symbol(symbol).companyName("Apple Inc.")
                .currentPrice(new BigDecimal(price)).build();
    }

    @Test
    void getMarketDataMapsEntityToResponse() {
        when(marketDataRepository.findBySymbol("AAPL")).thenReturn(Optional.of(market("AAPL", "200.00")));

        MarketDataResponse response = marketDataService.getMarketData("AAPL");

        assertThat(response.getSymbol()).isEqualTo("AAPL");
        assertThat(response.getCompanyName()).isEqualTo("Apple Inc.");
        assertThat(response.getCurrentPrice()).isEqualByComparingTo("200.00");
    }

    @Test
    void getMarketDataRejectsUnknownSymbol() {
        when(marketDataRepository.findBySymbol("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> marketDataService.getMarketData("NOPE"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Market data not found for symbol: NOPE");
    }

    @Test
    void getAllMarketDataMapsEveryRow() {
        when(marketDataRepository.findAll()).thenReturn(List.of(market("AAPL", "200.00"), market("MSFT", "300.00")));

        List<MarketDataResponse> responses = marketDataService.getAllMarketData();

        assertThat(responses).hasSize(2);
        assertThat(responses).extracting(MarketDataResponse::getSymbol).containsExactly("AAPL", "MSFT");
    }

    @Test
    void searchDelegatesToRepository() {
        when(marketDataRepository
                .findByCompanyNameContainingIgnoreCaseOrSymbolContainingIgnoreCase("app", "app"))
                .thenReturn(List.of(market("AAPL", "200.00")));

        List<MarketDataResponse> responses = marketDataService.searchMarketData("app");

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getSymbol()).isEqualTo("AAPL");
    }

    @Test
    void createMarketDataStampsLastUpdated() {
        when(marketDataRepository.save(any(MarketData.class))).thenAnswer(inv -> inv.getArgument(0));

        MarketDataResponse response = marketDataService.createMarketData(market("AAPL", "200.00"));

        assertThat(response.getSymbol()).isEqualTo("AAPL");
        ArgumentCaptor<MarketData> captor = ArgumentCaptor.forClass(MarketData.class);
        verify(marketDataRepository).save(captor.capture());
        assertThat(captor.getValue().getLastUpdated()).isNotNull();
    }

    @Test
    void updateMarketDataMergesFieldsOntoExistingRow() {
        MarketData existing = market("AAPL", "200.00");
        existing.setLastUpdated(LocalDateTime.now().minusDays(1));
        when(marketDataRepository.findBySymbol("AAPL")).thenReturn(Optional.of(existing));
        when(marketDataRepository.save(any(MarketData.class))).thenAnswer(inv -> inv.getArgument(0));

        MarketData update = MarketData.builder().symbol("AAPL").companyName("Apple Inc.")
                .currentPrice(new BigDecimal("210.00")).openPrice(new BigDecimal("205.00"))
                .highPrice(new BigDecimal("215.00")).lowPrice(new BigDecimal("200.00"))
                .previousClose(new BigDecimal("202.00")).volume(1000L)
                .change(new BigDecimal("8.00")).changePercent(new BigDecimal("3.96")).build();

        MarketDataResponse response = marketDataService.updateMarketData("AAPL", update);

        assertThat(response.getCurrentPrice()).isEqualByComparingTo("210.00");
        assertThat(response.getCompanyName()).isEqualTo("Apple Inc.");
        assertThat(response.getVolume()).isEqualTo(1000L);
        assertThat(response.getLastUpdated()).isNotNull();
    }

    @Test
    void updateMarketDataRejectsUnknownSymbol() {
        when(marketDataRepository.findBySymbol("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> marketDataService.updateMarketData("NOPE", market("NOPE", "1.00")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Market data not found for symbol: NOPE");

        verify(marketDataRepository, never()).save(any(MarketData.class));
    }

    @Test
    void deleteMarketDataRemovesExistingRow() {
        MarketData existing = market("AAPL", "200.00");
        when(marketDataRepository.findBySymbol("AAPL")).thenReturn(Optional.of(existing));

        marketDataService.deleteMarketData("AAPL");

        verify(marketDataRepository).delete(existing);
    }

    @Test
    void deleteMarketDataRejectsUnknownSymbol() {
        when(marketDataRepository.findBySymbol("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> marketDataService.deleteMarketData("NOPE"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Market data not found for symbol: NOPE");
    }
}
