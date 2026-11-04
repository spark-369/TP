package com.ftp.backend.service.impl;

import com.ftp.backend.dto.response.TransactionResponse;
import com.ftp.backend.entity.Order;
import com.ftp.backend.entity.Transaction;
import com.ftp.backend.entity.TransactionType;
import com.ftp.backend.entity.User;
import com.ftp.backend.exception.ForbiddenActionException;
import com.ftp.backend.exception.NotFoundException;
import com.ftp.backend.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the transaction ledger read model, including the ownership
 * check that stops one user reading another user's transaction.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionServiceImpl unit tests")
class TransactionServiceImplTest {

    private static final Long USER_ID = 5L;

    @Mock
    private TransactionRepository transactionRepository;

    private TransactionServiceImpl transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionServiceImpl(transactionRepository);
    }

    private Transaction transaction(Long id, Long ownerId) {
        return Transaction.builder()
                .id(id)
                .user(User.builder().id(ownerId).build())
                .order(Order.builder().id(100L).build())
                .transactionType(TransactionType.BUY)
                .symbol("AAPL")
                .quantity(new BigDecimal("1"))
                .price(new BigDecimal("200.00"))
                .totalAmount(new BigDecimal("200.00"))
                .fee(new BigDecimal("0.20"))
                .build();
    }

    @Test
    void getTransactionHistoryMapsEveryRow() {
        when(transactionRepository.findByUserId(USER_ID))
                .thenReturn(List.of(transaction(1L, USER_ID), transaction(2L, USER_ID)));

        List<TransactionResponse> responses = transactionService.getTransactionHistory(USER_ID);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getOrderId()).isEqualTo(100L);
        assertThat(responses.get(0).getTransactionType()).isEqualTo(TransactionType.BUY);
        assertThat(responses.get(0).getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void getTransactionMapsOwnedTransaction() {
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(transaction(1L, USER_ID)));

        TransactionResponse response = transactionService.getTransaction(USER_ID, 1L);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getSymbol()).isEqualTo("AAPL");
        assertThat(response.getFee()).isEqualByComparingTo("0.20");
    }

    @Test
    void getTransactionRejectsMissingTransaction() {
        when(transactionRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getTransaction(USER_ID, 9L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Transaction not found");
    }

    @Test
    void getTransactionRejectsAnotherUsersTransaction() {
        when(transactionRepository.findById(9L)).thenReturn(Optional.of(transaction(9L, 99L)));

        assertThatThrownBy(() -> transactionService.getTransaction(USER_ID, 9L))
                .isInstanceOf(ForbiddenActionException.class)
                .hasMessageContaining("not allowed to view this transaction");
    }
}
