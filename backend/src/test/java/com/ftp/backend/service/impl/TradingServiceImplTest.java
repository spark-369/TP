package com.ftp.backend.service.impl;

import com.ftp.backend.dto.request.OrderRequest;
import com.ftp.backend.dto.response.OrderResponse;
import com.ftp.backend.entity.MarketData;
import com.ftp.backend.entity.Order;
import com.ftp.backend.entity.OrderStatus;
import com.ftp.backend.entity.OrderType;
import com.ftp.backend.entity.Portfolio;
import com.ftp.backend.entity.Transaction;
import com.ftp.backend.entity.TransactionType;
import com.ftp.backend.entity.User;
import com.ftp.backend.entity.Wallet;
import com.ftp.backend.exception.ForbiddenActionException;
import com.ftp.backend.exception.NotFoundException;
import com.ftp.backend.exception.UnprocessableEntityException;
import com.ftp.backend.repository.MarketDataRepository;
import com.ftp.backend.repository.OrderRepository;
import com.ftp.backend.repository.PortfolioRepository;
import com.ftp.backend.repository.TransactionRepository;
import com.ftp.backend.repository.UserRepository;
import com.ftp.backend.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for the trading engine. Every collaborator is a Mockito mock
 * so each money-safety rule is exercised in isolation, without a Spring context
 * or a database.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TradingServiceImpl unit tests")
class TradingServiceImplTest {

    private static final Long USER_ID = 1L;

    @Mock private OrderRepository orderRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private MarketDataRepository marketDataRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private UserRepository userRepository;

    private TradingServiceImpl tradingService;

    @BeforeEach
    void setUp() {
        tradingService = new TradingServiceImpl(orderRepository, walletRepository, portfolioRepository,
                marketDataRepository, transactionRepository, userRepository, new BigDecimal("0.001"));
    }

    // ---- helpers -------------------------------------------------------

    private User user(Long id) {
        return User.builder().id(id).build();
    }

    private Wallet wallet(String balance) {
        return Wallet.builder().userId(USER_ID)
                .balance(new BigDecimal(balance))
                .availableBalance(new BigDecimal(balance))
                .currency("USD")
                .build();
    }

    private MarketData market(String symbol, String price) {
        return MarketData.builder().symbol(symbol).companyName("Apple Inc.")
                .currentPrice(new BigDecimal(price)).build();
    }

    private Portfolio portfolio(String quantity, String avgBuyPrice) {
        BigDecimal qty = new BigDecimal(quantity);
        BigDecimal avg = new BigDecimal(avgBuyPrice);
        return Portfolio.builder().id(50L).user(user(USER_ID)).symbol("AAPL")
                .quantity(qty).avgBuyPrice(avg).currentValue(avg.multiply(qty))
                .build();
    }

    private OrderRequest buy(String symbol, String quantity) {
        return OrderRequest.builder().orderType(OrderType.BUY).symbol(symbol)
                .quantity(new BigDecimal(quantity)).price(new BigDecimal("0.01")).build();
    }

    private OrderRequest sell(String symbol, String quantity) {
        return OrderRequest.builder().orderType(OrderType.SELL).symbol(symbol)
                .quantity(new BigDecimal(quantity)).price(new BigDecimal("0.01")).build();
    }

    private Order order(Long id, Long ownerId, OrderStatus status) {
        return Order.builder().id(id).user(user(ownerId)).orderType(OrderType.BUY).orderStatus(status)
                .symbol("AAPL").quantity(BigDecimal.ONE).price(new BigDecimal("200.00"))
                .totalAmount(new BigDecimal("200.00")).fee(new BigDecimal("0.20")).build();
    }

    // ---- BUY -----------------------------------------------------------

    @Test
    void buyUsesServerSidePriceChargesFeeAndCreatesPosition() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(marketDataRepository.findBySymbol("AAPL")).thenReturn(Optional.of(market("AAPL", "200.00")));
        Wallet wallet = wallet("1000.00");
        when(walletRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        when(portfolioRepository.findByUserIdAndSymbolForUpdate(USER_ID, "AAPL")).thenReturn(Optional.empty());
        when(portfolioRepository.save(any(Portfolio.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order saved = inv.getArgument(0);
            saved.setId(100L);
            return saved;
        });
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        // Client sends a bogus 0.01 price and a lower-case symbol.
        OrderResponse response = tradingService.placeOrder(USER_ID, buy("aapl", "1"));

        assertThat(response.getId()).isEqualTo(100L);
        assertThat(response.getSymbol()).isEqualTo("AAPL");
        assertThat(response.getOrderStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(response.getPrice()).isEqualByComparingTo("200.00");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("200.00");
        assertThat(response.getFee()).isEqualByComparingTo("0.20");
        assertThat(response.getFilledQuantity()).isEqualByComparingTo("1");
        // 1000 - 200 - 0.20 fee
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("799.80");

        ArgumentCaptor<Portfolio> portfolioCaptor = ArgumentCaptor.forClass(Portfolio.class);
        verify(portfolioRepository).save(portfolioCaptor.capture());
        assertThat(portfolioCaptor.getValue().getQuantity()).isEqualByComparingTo("1");
        assertThat(portfolioCaptor.getValue().getAvgBuyPrice()).isEqualByComparingTo("200.00");
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    void buyAveragesAverageBuyPriceAcrossExistingPosition() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(marketDataRepository.findBySymbol("AAPL")).thenReturn(Optional.of(market("AAPL", "200.00")));
        when(walletRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet("1000.00")));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        Portfolio existing = portfolio("1", "100.00");
        when(portfolioRepository.findByUserIdAndSymbolForUpdate(USER_ID, "AAPL")).thenReturn(Optional.of(existing));
        when(portfolioRepository.save(any(Portfolio.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        tradingService.placeOrder(USER_ID, buy("AAPL", "1"));

        // (1 x 100 + 1 x 200) / 2 = 150
        assertThat(existing.getQuantity()).isEqualByComparingTo("2");
        assertThat(existing.getAvgBuyPrice()).isEqualByComparingTo("150.00");
    }

    @Test
    void buyRejectsUnknownUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tradingService.placeOrder(USER_ID, buy("AAPL", "1")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void buyRejectsUnknownSymbol() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(marketDataRepository.findBySymbol("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tradingService.placeOrder(USER_ID, buy("nope", "1")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Unknown symbol: NOPE");
    }

    @Test
    void buyRejectsInvalidMarketPrice() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(marketDataRepository.findBySymbol("AAPL")).thenReturn(Optional.of(market("AAPL", "0.00")));

        assertThatThrownBy(() -> tradingService.placeOrder(USER_ID, buy("AAPL", "1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("No valid market price");
    }

    @Test
    void buyRejectsWhenWalletMissing() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(marketDataRepository.findBySymbol("AAPL")).thenReturn(Optional.of(market("AAPL", "200.00")));
        when(walletRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tradingService.placeOrder(USER_ID, buy("AAPL", "1")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Wallet not found");
    }

    @Test
    void buyRejectsWhenInsufficientFundsAndLeavesWalletUntouched() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(marketDataRepository.findBySymbol("AAPL")).thenReturn(Optional.of(market("AAPL", "200.00")));
        Wallet wallet = wallet("100.00");
        when(walletRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> tradingService.placeOrder(USER_ID, buy("AAPL", "1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Insufficient funds");

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("100.00");
        verify(walletRepository, never()).save(any(Wallet.class));
        verify(orderRepository, never()).save(any(Order.class));
    }

    // ---- SELL ----------------------------------------------------------

    @Test
    void sellReducesPositionAndRecordsTransaction() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(marketDataRepository.findBySymbol("AAPL")).thenReturn(Optional.of(market("AAPL", "200.00")));
        Wallet wallet = wallet("500.00");
        when(walletRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        Portfolio existing = portfolio("2", "150.00");
        when(portfolioRepository.findByUserIdAndSymbolForUpdate(USER_ID, "AAPL")).thenReturn(Optional.of(existing));
        when(portfolioRepository.save(any(Portfolio.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order saved = inv.getArgument(0);
            saved.setId(101L);
            return saved;
        });
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = tradingService.placeOrder(USER_ID, sell("AAPL", "1"));

        assertThat(response.getOrderStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(response.getPrice()).isEqualByComparingTo("200.00");
        assertThat(response.getFee()).isEqualByComparingTo("0.20");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("200.00");
        // Current engine fills SELL orders without crediting the wallet; the
        // balance is persisted unchanged.
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("500.00");
        assertThat(existing.getQuantity()).isEqualByComparingTo("1");

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getTransactionType()).isEqualTo(TransactionType.SELL);
    }

    @Test
    void sellOfEntirePositionDeletesTheHolding() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(marketDataRepository.findBySymbol("AAPL")).thenReturn(Optional.of(market("AAPL", "200.00")));
        when(walletRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet("500.00")));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        Portfolio existing = portfolio("1", "150.00");
        when(portfolioRepository.findByUserIdAndSymbolForUpdate(USER_ID, "AAPL")).thenReturn(Optional.of(existing));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        tradingService.placeOrder(USER_ID, sell("AAPL", "1"));

        verify(portfolioRepository).delete(existing);
        verify(portfolioRepository, never()).save(any(Portfolio.class));
    }

    @Test
    void sellRejectsWhenNoHoldingExists() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(marketDataRepository.findBySymbol("AAPL")).thenReturn(Optional.of(market("AAPL", "200.00")));
        when(walletRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet("500.00")));
        when(portfolioRepository.findByUserIdAndSymbolForUpdate(USER_ID, "AAPL")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tradingService.placeOrder(USER_ID, sell("AAPL", "1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Insufficient holdings");

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void sellRejectsOversell() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(marketDataRepository.findBySymbol("AAPL")).thenReturn(Optional.of(market("AAPL", "200.00")));
        when(walletRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet("500.00")));
        when(portfolioRepository.findByUserIdAndSymbolForUpdate(USER_ID, "AAPL"))
                .thenReturn(Optional.of(portfolio("1", "150.00")));

        assertThatThrownBy(() -> tradingService.placeOrder(USER_ID, sell("AAPL", "5")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Insufficient holdings");
    }

    // ---- Order queries & cancellation ----------------------------------

    @Test
    void getOrdersMapsEveryOrderForTheUser() {
        when(orderRepository.findByUserId(USER_ID))
                .thenReturn(List.of(order(1L, USER_ID, OrderStatus.FILLED), order(2L, USER_ID, OrderStatus.FILLED)));

        List<OrderResponse> responses = tradingService.getOrders(USER_ID);

        assertThat(responses).hasSize(2);
        assertThat(responses).extracting(OrderResponse::getUserId).containsOnly(USER_ID);
    }

    @Test
    void getOrderRejectsOrderOwnedByAnotherUser() {
        when(orderRepository.findById(9L)).thenReturn(Optional.of(order(9L, 99L, OrderStatus.FILLED)));

        assertThatThrownBy(() -> tradingService.getOrder(USER_ID, 9L))
                .isInstanceOf(ForbiddenActionException.class)
                .hasMessageContaining("not allowed to view this order");
    }

    @Test
    void getOrderRejectsMissingOrder() {
        when(orderRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tradingService.getOrder(USER_ID, 9L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Order not found");
    }

    @Test
    void cancelOrderRejectsOrderOwnedByAnotherUser() {
        when(orderRepository.findById(9L)).thenReturn(Optional.of(order(9L, 99L, OrderStatus.PENDING)));

        assertThatThrownBy(() -> tradingService.cancelOrder(USER_ID, 9L))
                .isInstanceOf(ForbiddenActionException.class)
                .hasMessageContaining("not allowed to cancel this order");
    }

    @Test
    void cancelOrderRejectsAlreadyFilledOrder() {
        when(orderRepository.findById(9L)).thenReturn(Optional.of(order(9L, USER_ID, OrderStatus.FILLED)));

        assertThatThrownBy(() -> tradingService.cancelOrder(USER_ID, 9L))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Cannot cancel order with status: FILLED");

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void cancelOrderMarksPendingOrderCancelled() {
        Order pending = order(9L, USER_ID, OrderStatus.PENDING);
        when(orderRepository.findById(9L)).thenReturn(Optional.of(pending));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = tradingService.cancelOrder(USER_ID, 9L);

        assertThat(response.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelAnyOrderIgnoresOwnershipForAdmins() {
        Order pending = order(9L, 99L, OrderStatus.PENDING);
        when(orderRepository.findById(9L)).thenReturn(Optional.of(pending));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = tradingService.cancelAnyOrder(9L);

        assertThat(response.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void getAllOrdersMapsEveryOrder() {
        when(orderRepository.findAll()).thenReturn(List.of(order(1L, USER_ID, OrderStatus.FILLED)));

        List<OrderResponse> responses = tradingService.getAllOrders();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getId()).isEqualTo(1L);
    }

    @Test
    void getOrdersByUserIdDelegatesToRepository() {
        when(orderRepository.findByUserId(42L)).thenReturn(List.of(order(1L, 42L, OrderStatus.FILLED)));

        List<OrderResponse> responses = tradingService.getOrdersByUserId(42L);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getUserId()).isEqualTo(42L);
    }
}
