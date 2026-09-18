SET SESSION sql_mode = CONCAT(@@sql_mode, ',NO_AUTO_VALUE_ON_ZERO');

-- Reference currencies used in the take-home scenario.
INSERT INTO currency_definitions (code, currency_type, decimals) VALUES
                                                                     ('ZAR', 'FIAT', 2),
                                                                     ('NGN', 'FIAT', 2),
                                                                     ('KES', 'FIAT', 2),
                                                                     ('USDT', 'CRYPTO', 6),
                                                                     ('USDC', 'CRYPTO', 6);

-- The SYSTEM merchant (id 0) is not a real customer.
INSERT INTO merchants (id, name, is_system) VALUES (0, 'EZEEBIT_SYSTEM', TRUE);

INSERT INTO accounts (merchant_id, currency_code, balance_minor)
SELECT 0, code, 0 FROM currency_definitions;

-- A couple of demo merchants
INSERT INTO merchants (id, name, is_system) VALUES
                                                (1, 'iStore Cape Town', FALSE),
                                                (2, 'Sunbet Online', FALSE);