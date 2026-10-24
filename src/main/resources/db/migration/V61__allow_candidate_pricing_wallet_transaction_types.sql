-- Migration V61: Allow new wallet transaction types for candidate pricing model
-- Supports JOB_ACCEPTANCE_FEE (19,000 NP fee on accepting paid offer) and PORTFOLIO_EXPORT_FEE (1,000 NP fee on publishing portfolio)

alter table wallet_transactions drop constraint if exists ck_wallet_transactions_type;

alter table wallet_transactions add constraint ck_wallet_transactions_type
    check (transaction_type in (
        'TOPUP',
        'SPEND',
        'REWARD',
        'REFUND',
        'ADJUSTMENT',
        'PREMIUM_PURCHASE',
        'ITEM_PURCHASE',
        'JOB_ACCEPTANCE_FEE',
        'PORTFOLIO_EXPORT_FEE',
        'BOOST',
        'SUBSCRIPTION'
    ));
