package com.ftp.backend.service.impl;

import com.ftp.backend.dto.request.DepositRequest;
import com.ftp.backend.dto.request.WithdrawRequest;
import com.ftp.backend.dto.response.WalletResponse;
import com.ftp.backend.entity.Wallet;
import com.ftp.backend.exception.UnprocessableEntityException;
import com.ftp.backend.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for wallet balance handling, including the "create on demand"
 * behaviour and the guards that keep a balance from going negative.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WalletServiceImpl unit tests")
class WalletServiceImplTest {

    private static final Long USER_ID = 7L;

    @Mock
    private WalletRepository walletRepository;

    private WalletServiceImpl walletService;

    @BeforeEach
    void setUp() {
        walletService = new WalletServiceImpl(walletRepository);
    }

    private Wallet wallet(String balance) {
        return Wallet.builder().id(1L).userId(USER_ID)
                .balance(new BigDecimal(balance))
                .availableBalance(new BigDecimal(balance))
                .currency("USD")
                .build();
    }

    private DepositRequest deposit(String amount) {
        return DepositRequest.builder().amount(new BigDecimal(amount)).currency("USD").build();
    }

    private WithdrawRequest withdraw(String amount) {
        return WithdrawRequest.builder().amount(new BigDecimal(amount)).currency("USD").build();
    }

    @Test
    void getBalanceReturnsExistingWallet() {
        when(walletRepository.findByUserId(USER_ID)).thenReturn(Optional.of(wallet("250.00")));

        WalletResponse response = walletService.getBalance(USER_ID);

        assertThat(response.getBalance()).isEqualByComparingTo("250.00");
        assertThat(response.getAvailableBalance()).isEqualByComparingTo("250.00");
        verify(walletRepository, never()).save(any(Wallet.class));
    }

    @Test
    void getBalanceCreatesZeroedWalletWhenMissing() {
        when(walletRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletResponse response = walletService.getBalance(USER_ID);

        assertThat(response.getBalance()).isEqualByComparingTo("0");
        assertThat(response.getAvailableBalance()).isEqualByComparingTo("0");
        verify(walletRepository).save(any(Wallet.class));
    }

    @Test
    void depositIncreasesBalanceAndAvailableBalance() {
        when(walletRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet("100.00")));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletResponse response = walletService.deposit(USER_ID, deposit("50.00"));

        assertThat(response.getBalance()).isEqualByComparingTo("150.00");
        assertThat(response.getAvailableBalance()).isEqualByComparingTo("150.00");
    }

    @Test
    void depositCreatesWalletWhenNoneExists() {
        when(walletRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletResponse response = walletService.deposit(USER_ID, deposit("25.00"));

        assertThat(response.getBalance()).isEqualByComparingTo("25.00");
        // one save to create the zeroed wallet, one save for the deposit
        verify(walletRepository, times(2)).save(any(Wallet.class));
    }

    @Test
    void depositRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> walletService.deposit(USER_ID, deposit("0.00")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Deposit amount must be greater than zero");

        verify(walletRepository, never()).findByUserIdForUpdate(any());
    }

    @Test
    void withdrawDecreasesBalance() {
        when(walletRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet("100.00")));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletResponse response = walletService.withdraw(USER_ID, withdraw("40.00"));

        assertThat(response.getBalance()).isEqualByComparingTo("60.00");
        assertThat(response.getAvailableBalance()).isEqualByComparingTo("60.00");
    }

    @Test
    void withdrawRejectsInsufficientFundsAndLeavesWalletUntouched() {
        Wallet wallet = wallet("10.00");
        when(walletRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> walletService.withdraw(USER_ID, withdraw("25.00")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Insufficient funds");

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("10.00");
        verify(walletRepository, never()).save(any(Wallet.class));
    }

    @Test
    void withdrawRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> walletService.withdraw(USER_ID, withdraw("-5.00")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Withdrawal amount must be greater than zero");
    }

    @Test
    void withdrawRejectsNullAmount() {
        assertThatThrownBy(() -> walletService.withdraw(USER_ID,
                WithdrawRequest.builder().currency("USD").build()))
                .isInstanceOf(UnprocessableEntityException.class);
    }
}
