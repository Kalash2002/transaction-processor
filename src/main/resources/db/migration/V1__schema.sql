CREATE TABLE accounts (
                          id        uuid PRIMARY KEY,
                          balance   numeric(19, 2) NOT NULL CHECK (balance >= 0),
                          opened_at timestamptz    NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
                                entry_id      uuid PRIMARY KEY,
    -- assigned inside the writing transaction, so it orders entries the way the balances
    -- actually changed. Two timestamps can share a ms and clocks move backwards.
                                sequence      bigint         NOT NULL GENERATED ALWAYS AS IDENTITY UNIQUE,
                                account_id    uuid           NOT NULL REFERENCES accounts (id),
                                type          text           NOT NULL,
                                amount        numeric(19, 2) NOT NULL CHECK (amount >= 0),
                                balance_after numeric(19, 2) NOT NULL CHECK (balance_after >= 0),
    -- now() is the transaction's start time, so both legs of a transfer share it exactly.
                                occurred_at   timestamptz    NOT NULL DEFAULT now(),
                                transfer_id   uuid
);

-- History is always read for one account in sequence order.
CREATE INDEX ledger_entries_by_account ON ledger_entries (account_id, sequence);
