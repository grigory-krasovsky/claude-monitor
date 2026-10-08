package com.example.claudeusagemonitor.account;

/** Состояние заявки. Данные о лимитах видит только {@link #APPROVED}. */
public enum AccountStatus {
    PENDING,
    APPROVED,
    REJECTED
}
