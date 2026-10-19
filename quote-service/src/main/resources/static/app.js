"use strict";

document.addEventListener("DOMContentLoaded", () => {
    // Form elements
    const tradeForm = document.getElementById("tradeForm");
    const clientInput = document.getElementById("client");
    const baseCurrencyInput = document.getElementById("baseCurrency");
    const quoteCurrencyInput = document.getElementById("quoteCurrency");
    const amountInput = document.getElementById("amount");
    const amountCurrency = document.getElementById("amountCurrency");

    // Buttons and messages
    const quoteButton = document.getElementById("quoteButton");
    const executeButton = document.getElementById("executeButton");
    const formError = document.getElementById("formError");
    const settlementMessage = document.getElementById("settlementMessage");

    // Market valuation
    const valuationEmpty = document.getElementById("valuationEmpty");
    const valuationResult = document.getElementById("valuationResult");

    const exchangeRateElement = document.getElementById("exchangeRate");
    const ratePairElement = document.getElementById("ratePair");
    const resultClient = document.getElementById("resultClient");
    const baseAmountElement = document.getElementById("baseAmount");
    const quoteAmountElement = document.getElementById("quoteAmount");
    const quoteReferenceElement = document.getElementById("quoteReference");

    // Settlement
    const settlementEmpty = document.getElementById("settlementEmpty");
    const settlementResult = document.getElementById("settlementResult");
    const settlementStatus = document.getElementById("settlementStatus");
    const settlementStatusText = document.getElementById("settlementStatusText");

    const transactionIdElement = document.getElementById("transactionId");
    const settledClientElement = document.getElementById("settledClient");
    const settledAmountElement = document.getElementById("settledAmount");
    const auditReferenceElement = document.getElementById("auditReference");

    /*
     * DEMO DATA ONLY.
     *
     * These fixed rates are illustrative, not live market rates.
     * Replace getDemoRate() with your quote-service API integration
     * before using actual market rates.
     */
    const demoRates = {
        USD: { EUR: 0.92, GBP: 0.79, INR: 83.50, JPY: 150.00,
               AUD: 1.52, CAD: 1.36, CHF: 0.88 },
        EUR: { USD: 1.09, GBP: 0.86, INR: 90.75, JPY: 163.00,
               AUD: 1.65, CAD: 1.48, CHF: 0.96 },
        GBP: { USD: 1.27, EUR: 1.16, INR: 105.70, JPY: 190.00,
               AUD: 1.93, CAD: 1.72, CHF: 1.12 },
        INR: { USD: 0.012, EUR: 0.011, GBP: 0.0095, JPY: 1.80,
               AUD: 0.018, CAD: 0.016, CHF: 0.0105 },
        JPY: { USD: 0.0067, EUR: 0.0061, GBP: 0.0053, INR: 0.56,
               AUD: 0.010, CAD: 0.0091, CHF: 0.0059 },
        AUD: { USD: 0.66, EUR: 0.61, GBP: 0.52, INR: 55.00,
               JPY: 98.70, CAD: 0.90, CHF: 0.58 },
        CAD: { USD: 0.74, EUR: 0.68, GBP: 0.58, INR: 61.40,
               JPY: 110.30, AUD: 1.11, CHF: 0.65 },
        CHF: { USD: 1.14, EUR: 1.04, GBP: 0.89, INR: 94.90,
               JPY: 170.50, AUD: 1.72, CAD: 1.54 }
    };

    // Current quote held in memory until the page is refreshed.
    let currentQuote = null;
    let isProcessing = false;

    function getDemoRate(base, quote) {
        if (base === quote) {
            return 1;
        }

        const rate = demoRates[base]?.[quote];

        if (typeof rate !== "number" || !Number.isFinite(rate) || rate <= 0) {
            throw new Error("No demo exchange rate is configured for this pair.");
        }

        return rate;
    }

    function createReference(prefix) {
        const randomPart = typeof crypto !== "undefined" &&
                           typeof crypto.randomUUID === "function"
            ? crypto.randomUUID().replaceAll("-", "").slice(0, 12).toUpperCase()
            : Math.random().toString(36).slice(2, 14).toUpperCase();

        return `${prefix}-${randomPart}`;
    }

    function formatMoney(value, currency) {
        return new Intl.NumberFormat("en-US", {
            style: "currency",
            currency,
            minimumFractionDigits: 2,
            maximumFractionDigits: 2
        }).format(value);
    }

    function formatRate(rate) {
        return rate.toLocaleString("en-US", {
            minimumFractionDigits: 4,
            maximumFractionDigits: 6
        });
    }

    function showMessage(element, message, type = "info") {
        element.textContent = message;
        element.className = `message ${type}-message`;
        element.hidden = false;
    }

    function hideMessage(element) {
        element.hidden = true;
        element.textContent = "";
    }

    function setButtonLoading(button, loading, label) {
        button.disabled = loading;
        button.classList.toggle("is-loading", loading);

        const text = button.querySelector("span:last-child");

        if (text) {
            text.textContent = label;
        }
    }

    function resetSettlement() {
        settlementEmpty.hidden = false;
        settlementResult.hidden = true;
        hideMessage(settlementMessage);

        executeButton.disabled = true;
        currentQuote = null;
    }

    function resetQuote() {
        valuationEmpty.hidden = false;
        valuationResult.hidden = true;

        hideMessage(formError);
        resetSettlement();
    }

    function updateAmountCurrency() {
        amountCurrency.textContent = baseCurrencyInput.value;
    }

    function validateTrade() {
        const client = clientInput.value;
        const base = baseCurrencyInput.value;
        const quote = quoteCurrencyInput.value;
        const amount = Number(amountInput.value);

        if (!client || !base || !quote) {
            throw new Error("Please complete all trade parameters.");
        }

        if (base === quote) {
            throw new Error("Please select two different currencies.");
        }

        if (!Number.isFinite(amount) || amount <= 0) {
            throw new Error("Enter a transaction amount greater than zero.");
        }

        if (amount > 1_000_000_000_000) {
            throw new Error("The transaction amount exceeds the permitted demo limit.");
        }

        return { client, base, quote, amount };
    }

    // STEP 1: Calculate a demo quote.
    tradeForm.addEventListener("submit", (event) => {
        event.preventDefault();

        if (isProcessing) {
            return;
        }

        hideMessage(formError);
        hideMessage(settlementMessage);

        try {
            const trade = validateTrade();
            const rate = getDemoRate(trade.base, trade.quote);
            const convertedAmount = trade.amount * rate;

            if (!Number.isFinite(convertedAmount)) {
                throw new Error("The converted amount is outside the supported range.");
            }

            // Invalidate any previous quote and settlement.
            resetSettlement();

            currentQuote = {
                ...trade,
                rate,
                convertedAmount,
                reference: createReference("QTE"),
                createdAt: new Date().toISOString()
            };

            // Populate valuation.
            exchangeRateElement.textContent = formatRate(rate);
            ratePairElement.textContent = `${trade.base} / ${trade.quote}`;
            resultClient.textContent = trade.client;
            baseAmountElement.textContent = formatMoney(trade.amount, trade.base);
            quoteAmountElement.textContent =
                formatMoney(convertedAmount, trade.quote);
            quoteReferenceElement.textContent = currentQuote.reference;

            valuationEmpty.hidden = true;
            valuationResult.hidden = false;

            executeButton.disabled = false;

            showMessage(
                settlementMessage,
                "Quote generated. Review the amount before confirming this demo trade.",
                "info"
            );
        } catch (error) {
            resetQuote();
            showMessage(formError, error.message, "error");
        }
    });

    // STEP 2: Execute a simulated settlement.
    executeButton.addEventListener("click", () => {
        if (isProcessing || !currentQuote) {
            return;
        }

        // Prevent settlement if the form has changed since the quote.
        const quoteIsCurrent =
            clientInput.value === currentQuote.client &&
            baseCurrencyInput.value === currentQuote.base &&
            quoteCurrencyInput.value === currentQuote.quote &&
            Number(amountInput.value) === currentQuote.amount;

        if (!quoteIsCurrent) {
            resetQuote();
            showMessage(
                formError,
                "Trade parameters changed. Generate a new quote before settlement.",
                "error"
            );
            return;
        }

        isProcessing = true;
        executeButton.disabled = true;
        quoteButton.disabled = true;

        try {
            /*
             * DEMO ONLY:
             * This generates local reference IDs. It does not call the
             * settlement service, transfer funds, or write a blockchain log.
             */
            const transactionId = createReference("TXN");
            const auditReference = createReference("AUD");

            transactionIdElement.textContent = transactionId;
            settledClientElement.textContent = currentQuote.client;
            settledAmountElement.textContent = formatMoney(
                currentQuote.convertedAmount,
                currentQuote.quote
            );
            auditReferenceElement.textContent = auditReference;

            settlementStatus.className = "settlement-status is-success";
            settlementStatusText.textContent = "Demo settlement successful";

            settlementEmpty.hidden = true;
            settlementResult.hidden = false;

            showMessage(
                settlementMessage,
                "Demo completed successfully. No funds were transferred and no persistent audit log was created.",
                "success"
            );
        } catch (error) {
            showMessage(
                settlementMessage,
                `Settlement could not be completed: ${error.message}`,
                "error"
            );
            executeButton.disabled = false;
        } finally {
            isProcessing = false;
            quoteButton.disabled = false;
        }
    });

    // Currency and trade changes invalidate the old quote.
    [
        clientInput,
        baseCurrencyInput,
        quoteCurrencyInput,
        amountInput
    ].forEach((element) => {
        element.addEventListener("change", () => {
            updateAmountCurrency();
            resetQuote();
        });

        element.addEventListener("input", () => {
            if (element === amountInput) {
                resetQuote();
            }
        });
    });

    // Initial state
    updateAmountCurrency();
    resetQuote();
});

