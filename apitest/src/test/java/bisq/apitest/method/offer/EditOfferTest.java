/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.apitest.method.offer;

import bisq.core.payment.PaymentAccount;

import bisq.proto.grpc.OfferInfo;

import io.grpc.StatusRuntimeException;

import java.math.BigDecimal;

import java.util.HashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static bisq.apitest.config.ApiTestConfig.BSQ;
import static bisq.apitest.config.ApiTestConfig.EUR;
import static bisq.apitest.config.ApiTestConfig.USD;
import static bisq.core.btc.wallet.Restrictions.getDefaultBuyerSecurityDepositAsPercent;
import static bisq.proto.grpc.EditOfferRequest.EditType.*;
import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static protobuf.OfferDirection.BUY;
import static protobuf.OfferDirection.SELL;

@SuppressWarnings("ALL")
@Disabled
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EditOfferTest extends AbstractOfferTest {

    // Some test fixtures to reduce duplication.
    private static final Map<String, PaymentAccount> paymentAcctCache = new HashMap<>();
    private static final String RUBLE = "RUB";
    private static final long AMOUNT = 10000000L;

    @Test
    @Order(1)
    public void testOfferDisableAndEnable() {
        PaymentAccount paymentAcct = getOrCreatePaymentAccount("DE");
        OfferInfo originalOffer = createMktPricedOfferForEdit(BUY.name(),
                EUR,
                paymentAcct.getId(),
                0.0,
                NO_TRIGGER_PRICE);
        log.debug("ORIGINAL EUR OFFER:\n{}", toOfferTable.apply(originalOffer));
        assertFalse(originalOffer.getIsActivated()); // Not activated until prep is done.
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.
        originalOffer = aliceClient.getMyOffer(originalOffer.getId());
        assertTrue(originalOffer.getIsActivated());
        // Disable offer
        aliceClient.editOfferActivationState(originalOffer.getId(), DEACTIVATE_OFFER);
        genBtcBlocksThenWait(1, 1500); // Wait for offer book removal.
        OfferInfo editedOffer = aliceClient.getMyOffer(originalOffer.getId());
        log.debug("EDITED EUR OFFER:\n{}", toOfferTable.apply(editedOffer));
        assertFalse(editedOffer.getIsActivated());
        // Re-enable offer
        aliceClient.editOfferActivationState(editedOffer.getId(), ACTIVATE_OFFER);
        genBtcBlocksThenWait(1, 1500); // Wait for offer book re-entry.
        editedOffer = aliceClient.getMyOffer(originalOffer.getId());
        log.debug("EDITED EUR OFFER:\n{}", toOfferTable.apply(editedOffer));
        assertTrue(editedOffer.getIsActivated());

        doSanityCheck(originalOffer, editedOffer);
    }

    @Test
    @Order(2)
    public void testEditTriggerPrice() {
        PaymentAccount paymentAcct = getOrCreatePaymentAccount("FI");
        OfferInfo originalOffer = createMktPricedOfferForEdit(SELL.name(),
                EUR,
                paymentAcct.getId(),
                0.0,
                NO_TRIGGER_PRICE);
        log.debug("ORIGINAL EUR OFFER:\n{}", toOfferTable.apply(originalOffer));
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.
        originalOffer = aliceClient.getMyOffer(originalOffer.getId());
        assertEquals(0 /*no trigger price*/, originalOffer.getTriggerPrice());

        // Edit the offer's trigger price, nothing else.
        var mktPrice = aliceClient.getBtcPrice(EUR);
        var delta = 5_000.00;
        var newTriggerPriceAsLong = calcPriceAsLong.apply(mktPrice, delta);

        aliceClient.editOfferTriggerPrice(originalOffer.getId(), newTriggerPriceAsLong);
        sleep(2500); // Wait for offer book re-entry.
        OfferInfo editedOffer = aliceClient.getMyOffer(originalOffer.getId());
        log.debug("EDITED EUR OFFER:\n{}", toOfferTable.apply(editedOffer));
        assertEquals(newTriggerPriceAsLong, editedOffer.getTriggerPrice());
        assertTrue(editedOffer.getUseMarketBasedPrice());

        doSanityCheck(originalOffer, editedOffer);
    }

    @Test
    @Order(3)
    public void testSetTriggerPriceToNegativeValueShouldThrowException() {
        PaymentAccount paymentAcct = getOrCreatePaymentAccount("FI");
        final OfferInfo originalOffer = createMktPricedOfferForEdit(SELL.name(),
                EUR,
                paymentAcct.getId(),
                0.0,
                NO_TRIGGER_PRICE);
        log.debug("ORIGINAL EUR OFFER:\n{}", toOfferTable.apply(originalOffer));
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.
        // Edit the offer's trigger price, set to -1, check error.
        Throwable exception = assertThrows(StatusRuntimeException.class, () ->
                aliceClient.editOfferTriggerPrice(originalOffer.getId(), -1L));
        String expectedExceptionMessage =
                format("UNKNOWN: programmer error: cannot set trigger price to a negative value in offer with id '%s'",
                        originalOffer.getId());
        assertEquals(expectedExceptionMessage, exception.getMessage());
    }

    @Test
    @Order(4)
    public void testEditMktPriceMargin() {
        PaymentAccount paymentAcct = getOrCreatePaymentAccount("US");
        var originalMktPriceMargin = new BigDecimal("0.1").doubleValue();
        OfferInfo originalOffer = createMktPricedOfferForEdit(SELL.name(),
                USD,
                paymentAcct.getId(),
                originalMktPriceMargin,
                NO_TRIGGER_PRICE);
        log.debug("ORIGINAL USD OFFER:\n{}", toOfferTable.apply(originalOffer));
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.
        assertEquals(scaledDownMktPriceMargin.apply(originalMktPriceMargin), originalOffer.getMarketPriceMargin());
        // Edit the offer's price margin, nothing else.
        var newMktPriceMargin = new BigDecimal("0.5").doubleValue();
        aliceClient.editOfferPriceMargin(originalOffer.getId(), newMktPriceMargin);
        OfferInfo editedOffer = aliceClient.getMyOffer(originalOffer.getId());
        log.debug("EDITED USD OFFER:\n{}", toOfferTable.apply(editedOffer));
        assertEquals(scaledDownMktPriceMargin.apply(newMktPriceMargin), editedOffer.getMarketPriceMargin());

        doSanityCheck(originalOffer, editedOffer);
    }

    @Test
    @Order(5)
    public void testEditFixedPrice() {
        PaymentAccount paymentAcct = getOrCreatePaymentAccount("RU");
        double mktPriceAsDouble = aliceClient.getBtcPrice(RUBLE);
        String fixedPriceAsString = calcPriceAsString.apply(mktPriceAsDouble, 200_000.0000);
        OfferInfo originalOffer = createFixedPricedOfferForEdit(BUY.name(),
                RUBLE,
                paymentAcct.getId(),
                fixedPriceAsString);
        log.debug("ORIGINAL RUB OFFER:\n{}", toOfferTable.apply(originalOffer));
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.
        // Edit the offer's fixed price, nothing else.
        String editedFixedPriceAsString = calcPriceAsString.apply(mktPriceAsDouble, 100_000.0000);
        aliceClient.editOfferFixedPrice(originalOffer.getId(), editedFixedPriceAsString);
        // Wait for edited offer to be removed from offer-book, edited, and re-published.
        genBtcBlocksThenWait(1, 2500);
        OfferInfo editedOffer = aliceClient.getMyOffer(originalOffer.getId());
        log.debug("EDITED RUB OFFER:\n{}", toOfferTable.apply(editedOffer));
        var expectedNewFixedPrice = scaledUpFiatOfferPrice.apply(new BigDecimal(editedFixedPriceAsString));
        assertEquals(expectedNewFixedPrice, editedOffer.getPrice());
        assertFalse(editedOffer.getUseMarketBasedPrice());

        doSanityCheck(originalOffer, editedOffer);
    }

    @Test
    @Order(6)
    public void testEditFixedPriceAndDeactivation() {
        PaymentAccount paymentAcct = getOrCreatePaymentAccount("RU");
        double mktPriceAsDouble = aliceClient.getBtcPrice(RUBLE);
        String fixedPriceAsString = calcPriceAsString.apply(mktPriceAsDouble, 200_000.0000);
        OfferInfo originalOffer = createFixedPricedOfferForEdit(BUY.name(),
                RUBLE,
                paymentAcct.getId(),
                fixedPriceAsString);
        log.debug("ORIGINAL RUB OFFER:\n{}", toOfferTable.apply(originalOffer));
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.
        // Edit the offer's fixed price and deactivate it.
        String editedFixedPriceAsString = calcPriceAsString.apply(mktPriceAsDouble, 100_000.0000);
        aliceClient.editOffer(originalOffer.getId(),
                editedFixedPriceAsString,
                originalOffer.getUseMarketBasedPrice(),
                0.0,
                NO_TRIGGER_PRICE,
                DEACTIVATE_OFFER,
                FIXED_PRICE_AND_ACTIVATION_STATE);
        // Wait for edited offer to be removed from offer-book, edited, and re-published.
        genBtcBlocksThenWait(1, 2500);
        OfferInfo editedOffer = aliceClient.getMyOffer(originalOffer.getId());
        log.debug("EDITED RUB OFFER:\n{}", toOfferTable.apply(editedOffer));
        var expectedNewFixedPrice = scaledUpFiatOfferPrice.apply(new BigDecimal(editedFixedPriceAsString));
        assertEquals(expectedNewFixedPrice, editedOffer.getPrice());
        assertFalse(editedOffer.getIsActivated());

        doSanityCheck(originalOffer, editedOffer);
    }

    @Test
    @Order(7)
    public void testEditMktPriceMarginAndDeactivation() {
        PaymentAccount paymentAcct = getOrCreatePaymentAccount("US");

        var originalMktPriceMargin = new BigDecimal("0.0").doubleValue();
        OfferInfo originalOffer = createMktPricedOfferForEdit(SELL.name(),
                USD,
                paymentAcct.getId(),
                originalMktPriceMargin,
                0);
        log.debug("ORIGINAL USD OFFER:\n{}", toOfferTable.apply(originalOffer));
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.
        originalOffer = aliceClient.getMyOffer(originalOffer.getId());
        assertEquals(scaledDownMktPriceMargin.apply(originalMktPriceMargin), originalOffer.getMarketPriceMargin());

        // Edit the offer's price margin and trigger price, and deactivate it.
        var newMktPriceMargin = new BigDecimal("1.50").doubleValue();
        aliceClient.editOffer(originalOffer.getId(),
                "0.00",
                originalOffer.getUseMarketBasedPrice(),
                newMktPriceMargin,
                0,
                DEACTIVATE_OFFER,
                MKT_PRICE_MARGIN_AND_ACTIVATION_STATE);
        // Wait for edited offer to be removed from offer-book, edited, and re-published.
        genBtcBlocksThenWait(1, 2500);
        OfferInfo editedOffer = aliceClient.getMyOffer(originalOffer.getId());
        log.debug("EDITED USD OFFER:\n{}", toOfferTable.apply(editedOffer));
        assertEquals(scaledDownMktPriceMargin.apply(newMktPriceMargin), editedOffer.getMarketPriceMargin());
        assertEquals(0, editedOffer.getTriggerPrice());
        assertFalse(editedOffer.getIsActivated());

        doSanityCheck(originalOffer, editedOffer);
    }

    @Test
    @Order(8)
    public void testEditMktPriceMarginAndTriggerPriceAndDeactivation() {
        PaymentAccount paymentAcct = getOrCreatePaymentAccount("US");

        var originalMktPriceMargin = new BigDecimal("0.0").doubleValue();
        var mktPriceAsDouble = aliceClient.getBtcPrice(USD);
        var originalTriggerPriceAsLong = calcPriceAsLong.apply(mktPriceAsDouble, -5_000.0000);

        OfferInfo originalOffer = createMktPricedOfferForEdit(SELL.name(),
                USD,
                paymentAcct.getId(),
                originalMktPriceMargin,
                originalTriggerPriceAsLong);
        log.debug("ORIGINAL USD OFFER:\n{}", toOfferTable.apply(originalOffer));
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.
        originalOffer = aliceClient.getMyOffer(originalOffer.getId());
        assertEquals(scaledDownMktPriceMargin.apply(originalMktPriceMargin), originalOffer.getMarketPriceMargin());
        assertEquals(originalTriggerPriceAsLong, originalOffer.getTriggerPrice());

        // Edit the offer's price margin and trigger price, and deactivate it.
        var newMktPriceMargin = new BigDecimal("0.1").doubleValue();
        var newTriggerPriceAsLong = calcPriceAsLong.apply(mktPriceAsDouble, -2_000.0000);
        aliceClient.editOffer(originalOffer.getId(),
                "0.00",
                originalOffer.getUseMarketBasedPrice(),
                newMktPriceMargin,
                newTriggerPriceAsLong,
                DEACTIVATE_OFFER,
                MKT_PRICE_MARGIN_AND_TRIGGER_PRICE_AND_ACTIVATION_STATE);
        // Wait for edited offer to be removed from offer-book, edited, and re-published.
        genBtcBlocksThenWait(1, 2500);
        OfferInfo editedOffer = aliceClient.getMyOffer(originalOffer.getId());
        log.debug("EDITED USD OFFER:\n{}", toOfferTable.apply(editedOffer));
        assertEquals(scaledDownMktPriceMargin.apply(newMktPriceMargin), editedOffer.getMarketPriceMargin());
        assertEquals(newTriggerPriceAsLong, editedOffer.getTriggerPrice());
        assertFalse(editedOffer.getIsActivated());

        doSanityCheck(originalOffer, editedOffer);
    }

    @Test
    @Order(9)
    public void testEditingFixedPriceInMktPriceMarginBasedOfferShouldThrowException() {
        PaymentAccount paymentAcct = getOrCreatePaymentAccount("US");
        var originalMktPriceMargin = new BigDecimal("0.0").doubleValue();
        final OfferInfo originalOffer = createMktPricedOfferForEdit(SELL.name(),
                USD,
                paymentAcct.getId(),
                originalMktPriceMargin,
                NO_TRIGGER_PRICE);
        log.debug("ORIGINAL USD OFFER:\n{}", toOfferTable.apply(originalOffer));
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.
        // Try to edit both the fixed price and mkt price margin.
        var newMktPriceMargin = new BigDecimal("0.25").doubleValue();
        var newFixedPrice = "50000.0000";
        Throwable exception = assertThrows(StatusRuntimeException.class, () ->
                aliceClient.editOffer(originalOffer.getId(),
                        newFixedPrice,
                        originalOffer.getUseMarketBasedPrice(),
                        newMktPriceMargin,
                        NO_TRIGGER_PRICE,
                        ACTIVATE_OFFER,
                        MKT_PRICE_MARGIN_ONLY));
        String expectedExceptionMessage =
                format("UNKNOWN: programmer error: cannot set fixed price (%s) in"
                                + " mkt price margin based offer with id '%s'",
                        newFixedPrice,
                        originalOffer.getId());
        assertEquals(expectedExceptionMessage, exception.getMessage());
    }

    @Test
    @Order(10)
    public void testEditingTriggerPriceInFixedPriceOfferShouldThrowException() {
        PaymentAccount paymentAcct = getOrCreatePaymentAccount("RU");
        double mktPriceAsDouble = aliceClient.getBtcPrice(RUBLE);
        String fixedPriceAsString = calcPriceAsString.apply(mktPriceAsDouble, 200_000.0000);
        OfferInfo originalOffer = createFixedPricedOfferForEdit(BUY.name(),
                RUBLE,
                paymentAcct.getId(),
                fixedPriceAsString);
        log.debug("ORIGINAL RUB OFFER:\n{}", toOfferTable.apply(originalOffer));
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.
        long newTriggerPrice = 1000000L;
        Throwable exception = assertThrows(StatusRuntimeException.class, () ->
                aliceClient.editOfferTriggerPrice(originalOffer.getId(), newTriggerPrice));
        String expectedExceptionMessage =
                format("UNKNOWN: programmer error: cannot set a trigger price (%s) in"
                                + " fixed price offer with id '%s'",
                        newTriggerPrice,
                        originalOffer.getId());
        assertEquals(expectedExceptionMessage, exception.getMessage());
    }

    @Test
    @Order(11)
    public void testChangeFixedPriceOfferToPriceMarginBasedOfferWithTriggerPrice() {
        PaymentAccount paymentAcct = getOrCreatePaymentAccount("MX");
        double mktPriceAsDouble = aliceClient.getBtcPrice("MXN");
        String fixedPriceAsString = calcPriceAsString.apply(mktPriceAsDouble, 0.00);
        OfferInfo originalOffer = createFixedPricedOfferForEdit(BUY.name(),
                "MXN",
                paymentAcct.getId(),
                fixedPriceAsString);
        log.debug("ORIGINAL MXN OFFER:\n{}", toOfferTable.apply(originalOffer));
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.

        // Change the offer to mkt price based and set a trigger price.
        var newMktPriceMargin = new BigDecimal("0.05").doubleValue();
        var delta = 200_000.0000; // trigger price on buy offer is 200K above mkt price
        var newTriggerPriceAsLong = calcPriceAsLong.apply(mktPriceAsDouble, delta);
        aliceClient.editOffer(originalOffer.getId(),
                "0.00",
                true,
                newMktPriceMargin,
                newTriggerPriceAsLong,
                ACTIVATE_OFFER,
                MKT_PRICE_MARGIN_AND_TRIGGER_PRICE);
        // Wait for edited offer to be removed from offer-book, edited, and re-published.
        genBtcBlocksThenWait(1, 2500);
        OfferInfo editedOffer = aliceClient.getMyOffer(originalOffer.getId());
        log.debug("EDITED MXN OFFER:\n{}", toOfferTable.apply(editedOffer));
        assertTrue(editedOffer.getUseMarketBasedPrice());
        assertEquals(scaledDownMktPriceMargin.apply(newMktPriceMargin), editedOffer.getMarketPriceMargin());
        assertEquals(newTriggerPriceAsLong, editedOffer.getTriggerPrice());
        assertTrue(editedOffer.getIsActivated());

        doSanityCheck(originalOffer, editedOffer);
    }

    @Test
    @Order(12)
    public void testChangePriceMarginBasedOfferToFixedPriceOfferAndDeactivateIt() {
        PaymentAccount paymentAcct = getOrCreatePaymentAccount("GB");
        double mktPriceAsDouble = aliceClient.getBtcPrice("GBP");
        var originalMktPriceMargin = new BigDecimal("0.25").doubleValue();
        var delta = 1_000.0000; // trigger price on sell offer is 1K below mkt price
        var originalTriggerPriceAsLong = calcPriceAsLong.apply(mktPriceAsDouble, delta);
        final OfferInfo originalOffer = createMktPricedOfferForEdit(SELL.name(),
                "GBP",
                paymentAcct.getId(),
                originalMktPriceMargin,
                originalTriggerPriceAsLong);
        log.debug("ORIGINAL GBP OFFER:\n{}", toOfferTable.apply(originalOffer));
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.

        String fixedPriceAsString = calcPriceAsString.apply(mktPriceAsDouble, 0.00);
        aliceClient.editOffer(originalOffer.getId(),
                fixedPriceAsString,
                false,
                0.00,
                0,
                DEACTIVATE_OFFER,
                FIXED_PRICE_AND_ACTIVATION_STATE);
        // Wait for edited offer to be removed from offer-book, edited, and re-published.
        genBtcBlocksThenWait(1, 2500);
        OfferInfo editedOffer = aliceClient.getMyOffer(originalOffer.getId());
        log.debug("EDITED GBP OFFER:\n{}", toOfferTable.apply(editedOffer));
        assertEquals(scaledUpFiatOfferPrice.apply(new BigDecimal(fixedPriceAsString)), editedOffer.getPrice());
        assertFalse(editedOffer.getUseMarketBasedPrice());
        assertEquals(0.00, editedOffer.getMarketPriceMargin());
        assertEquals(0, editedOffer.getTriggerPrice());
        assertFalse(editedOffer.getIsActivated());
    }

    @Test
    @Order(13)
    public void testChangeFixedPricedBsqOfferToPriceMarginBasedOfferShouldThrowException() {
        OfferInfo originalOffer = aliceClient.createFixedPricedOffer(BUY.name(),
                BSQ,
                100_000_000L,
                100_000_000L,
                "0.00005",   // FIXED PRICE IN BTC (satoshis) FOR 1 BSQ
                getDefaultBuyerSecurityDepositAsPercent(),
                alicesLegacyBsqAcct.getId(),
                BSQ);
        log.debug("ORIGINAL BSQ OFFER:\n{}", toOfferTable.apply(originalOffer));
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.
        Throwable exception = assertThrows(StatusRuntimeException.class, () ->
                aliceClient.editOffer(originalOffer.getId(),
                        "0.00",
                        true,
                        0.1,
                        0,
                        ACTIVATE_OFFER,
                        MKT_PRICE_MARGIN_ONLY));
        String expectedExceptionMessage = format("UNKNOWN: cannot set mkt price margin or"
                        + " trigger price on fixed price altcoin offer with id '%s'",
                originalOffer.getId());
        assertEquals(expectedExceptionMessage, exception.getMessage());
    }

    @Test
    @Order(14)
    public void testEditTriggerPriceOnFixedPriceBsqOfferShouldThrowException() {
        OfferInfo originalOffer = aliceClient.createFixedPricedOffer(BUY.name(),
                BSQ,
                100_000_000L,
                100_000_000L,
                "0.00005",   // FIXED PRICE IN BTC (satoshis) FOR 1 BSQ
                getDefaultBuyerSecurityDepositAsPercent(),
                alicesLegacyBsqAcct.getId(),
                BSQ);
        log.debug("ORIGINAL BSQ OFFER:\n{}", toOfferTable.apply(originalOffer));
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.
        var newTriggerPriceAsLong = calcPriceAsLong.apply(0.00005, 0.00);
        Throwable exception = assertThrows(StatusRuntimeException.class, () ->
                aliceClient.editOffer(originalOffer.getId(),
                        "0.00",
                        false,
                        0.1,
                        newTriggerPriceAsLong,
                        ACTIVATE_OFFER,
                        TRIGGER_PRICE_ONLY));
        String expectedExceptionMessage = format("UNKNOWN: cannot set mkt price margin or"
                        + " trigger price on fixed price altcoin offer with id '%s'",
                originalOffer.getId());
        assertEquals(expectedExceptionMessage, exception.getMessage());
    }

    @Test
    @Order(15)
    public void testEditFixedPriceOnBsqOffer() {
        String fixedPriceAsString = "0.00005"; // FIXED PRICE IN BTC (satoshis) FOR 1 BSQ
        final OfferInfo originalOffer = aliceClient.createFixedPricedOffer(BUY.name(),
                BSQ,
                100_000_000L,
                100_000_000L,
                fixedPriceAsString,
                getDefaultBuyerSecurityDepositAsPercent(),
                alicesLegacyBsqAcct.getId(),
                BSQ);
        log.debug("ORIGINAL BSQ OFFER:\n{}", toOfferTable.apply(originalOffer));
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.
        String newFixedPriceAsString = "0.00003111";
        aliceClient.editOffer(originalOffer.getId(),
                newFixedPriceAsString,
                false,
                0.0,
                0,
                ACTIVATE_OFFER,
                FIXED_PRICE_ONLY);
        // Wait for edited offer to be edited and removed from offer-book.
        genBtcBlocksThenWait(1, 2500);
        OfferInfo editedOffer = aliceClient.getMyOffer(originalOffer.getId());
        log.debug("EDITED BSQ OFFER:\n{}", toOfferTable.apply(editedOffer));
        assertEquals(scaledUpAltcoinOfferPrice.apply(newFixedPriceAsString), editedOffer.getPrice());
        assertTrue(editedOffer.getIsActivated());
        assertFalse(editedOffer.getUseMarketBasedPrice());
        assertEquals(0.00, editedOffer.getMarketPriceMargin());
        assertEquals(0, editedOffer.getTriggerPrice());
    }

    @Test
    @Order(16)
    public void testDisableBsqOffer() {
        String fixedPriceAsString = "0.00005"; // FIXED PRICE IN BTC (satoshis) FOR 1 BSQ
        final OfferInfo originalOffer = aliceClient.createFixedPricedOffer(BUY.name(),
                BSQ,
                100_000_000L,
                100_000_000L,
                fixedPriceAsString,
                getDefaultBuyerSecurityDepositAsPercent(),
                alicesLegacyBsqAcct.getId(),
                BSQ);
        log.debug("ORIGINAL BSQ OFFER:\n{}", toOfferTable.apply(originalOffer));
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.
        aliceClient.editOffer(originalOffer.getId(),
                fixedPriceAsString,
                false,
                0.0,
                0,
                DEACTIVATE_OFFER,
                ACTIVATION_STATE_ONLY);
        // Wait for edited offer to be removed from offer-book.
        genBtcBlocksThenWait(1, 2500);
        OfferInfo editedOffer = aliceClient.getMyOffer(originalOffer.getId());
        log.debug("EDITED BSQ OFFER:\n{}", toOfferTable.apply(editedOffer));
        assertFalse(editedOffer.getIsActivated());
        assertEquals(scaledUpAltcoinOfferPrice.apply(fixedPriceAsString), editedOffer.getPrice());
        assertFalse(editedOffer.getUseMarketBasedPrice());
        assertEquals(0.00, editedOffer.getMarketPriceMargin());
        assertEquals(0, editedOffer.getTriggerPrice());
    }

    @Test
    @Order(17)
    public void testEditFixedPriceAndDisableBsqOffer() {
        String fixedPriceAsString = "0.00005"; // FIXED PRICE IN BTC (satoshis) FOR 1 BSQ
        final OfferInfo originalOffer = aliceClient.createFixedPricedOffer(BUY.name(),
                BSQ,
                100_000_000L,
                100_000_000L,
                fixedPriceAsString,
                getDefaultBuyerSecurityDepositAsPercent(),
                alicesLegacyBsqAcct.getId(),
                BSQ);
        log.debug("ORIGINAL BSQ OFFER:\n{}", toOfferTable.apply(originalOffer));
        genBtcBlocksThenWait(1, 2500); // Wait for offer book entry.
        String newFixedPriceAsString = "0.000045";
        aliceClient.editOffer(originalOffer.getId(),
                newFixedPriceAsString,
                false,
                0.0,
                0,
                DEACTIVATE_OFFER,
                FIXED_PRICE_AND_ACTIVATION_STATE);
        // Wait for edited offer to be edited and removed from offer-book.
        genBtcBlocksThenWait(1, 2500);
        OfferInfo editedOffer = aliceClient.getMyOffer(originalOffer.getId());
        log.debug("EDITED BSQ OFFER:\n{}", toOfferTable.apply(editedOffer));
        assertFalse(editedOffer.getIsActivated());
        assertEquals(scaledUpAltcoinOfferPrice.apply(newFixedPriceAsString), editedOffer.getPrice());
        assertFalse(editedOffer.getUseMarketBasedPrice());
        assertEquals(0.00, editedOffer.getMarketPriceMargin());
        assertEquals(0, editedOffer.getTriggerPrice());
    }

    private OfferInfo createMktPricedOfferForEdit(String direction,
                                                  String currencyCode,
                                                  String paymentAccountId,
                                                  double marketPriceMargin,
                                                  long triggerPrice) {
        return aliceClient.createMarketBasedPricedOffer(direction,
                currencyCode,
                AMOUNT,
                AMOUNT,
                marketPriceMargin,
                getDefaultBuyerSecurityDepositAsPercent(),
                paymentAccountId,
                BSQ,
                triggerPrice);
    }

    private OfferInfo createFixedPricedOfferForEdit(String direction,
                                                    String currencyCode,
                                                    String paymentAccountId,
                                                    String priceAsString) {
        return aliceClient.createFixedPricedOffer(direction,
                currencyCode,
                AMOUNT,
                AMOUNT,
                priceAsString,
                getDefaultBuyerSecurityDepositAsPercent(),
                paymentAccountId,
                BSQ);
    }

    private void doSanityCheck(OfferInfo originalOffer, OfferInfo editedOffer) {
        // Assert some of the immutable offer fields are unchanged.
        assertEquals(originalOffer.getDirection(), editedOffer.getDirection());
        assertEquals(originalOffer.getAmount(), editedOffer.getAmount());
        assertEquals(originalOffer.getMinAmount(), editedOffer.getMinAmount());
        assertEquals(originalOffer.getTxFee(), editedOffer.getTxFee());
        assertEquals(originalOffer.getMakerFee(), editedOffer.getMakerFee());
        assertEquals(originalOffer.getPaymentAccountId(), editedOffer.getPaymentAccountId());
        assertEquals(originalOffer.getDate(), editedOffer.getDate());
        if (originalOffer.getDirection().equals(BUY.name()))
            assertEquals(originalOffer.getBuyerSecurityDeposit(), editedOffer.getBuyerSecurityDeposit());
        else
            assertEquals(originalOffer.getSellerSecurityDeposit(), editedOffer.getSellerSecurityDeposit());
    }

    private PaymentAccount getOrCreatePaymentAccount(String countryCode) {
        if (paymentAcctCache.containsKey(countryCode)) {
            return paymentAcctCache.get(countryCode);
        } else {
            PaymentAccount paymentAcct = createDummyF2FAccount(aliceClient, countryCode);
            paymentAcctCache.put(countryCode, paymentAcct);
            return paymentAcct;
        }
    }

    @AfterAll
    public static void clearPaymentAcctCache() {
        paymentAcctCache.clear();
    }
}
