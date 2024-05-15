/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.pay.ios.apple;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.pay.FreeTrialPeriod;
import com.badlogic.gdx.pay.GdxPayException;
import com.badlogic.gdx.pay.Information;
import com.badlogic.gdx.pay.Offer;
import com.badlogic.gdx.pay.PurchaseManager;
import com.badlogic.gdx.pay.PurchaseManagerConfig;
import com.badlogic.gdx.pay.PurchaseObserver;
import com.badlogic.gdx.pay.Transaction;

import org.moe.natj.general.Pointer;
import org.moe.natj.general.ann.Owned;
import org.moe.natj.objc.ann.Selector;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.annotation.Nullable;

import apple.foundation.NSArray;
import apple.foundation.NSBundle;
import apple.foundation.NSData;
import apple.foundation.NSDate;
import apple.foundation.NSError;
import apple.foundation.NSLocale;
import apple.foundation.NSMutableSet;
import apple.foundation.NSNumberFormatter;
import apple.foundation.NSURL;
import apple.foundation.c.Foundation;
import apple.foundation.enums.NSNumberFormatterBehavior;
import apple.foundation.enums.NSNumberFormatterStyle;
import apple.storekit.SKPayment;
import apple.storekit.SKPaymentQueue;
import apple.storekit.SKPaymentTransaction;
import apple.storekit.SKProduct;
import apple.storekit.SKProductDiscount;
import apple.storekit.SKProductSubscriptionPeriod;
import apple.storekit.SKProductsRequest;
import apple.storekit.SKProductsResponse;
import apple.storekit.SKReceiptRefreshRequest;
import apple.storekit.SKRequest;
import apple.storekit.enums.SKErrorCode;
import apple.storekit.enums.SKPaymentTransactionState;
import apple.storekit.protocol.SKPaymentTransactionObserver;
import apple.storekit.protocol.SKProductsRequestDelegate;
import apple.storekit.protocol.SKRequestDelegate;

/**
 * The purchase manager implementation for Apple's iOS IAP system (iOS-MOE).
 *
 * @author Ioannis Giannakakis
 * @author HD_92 (BlueRiverInteractive)
 * @author noblemaster
 * @author alex-dorokhov
 */
public class PurchaseManageriOSApple implements PurchaseManager, SKPaymentTransactionObserver {

    static {
        try {
            // Fix NatJ runtime class initialization order for binding classes.
            Class.forName(SKPaymentTransaction.class.getName());
            Class.forName(SKProduct.class.getName());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static final String TAG = "GdxPay/AppleIOS";
    private static final boolean LOGDEBUG = true;
    private static final int LOGTYPELOG = 0;
    private static final int LOGTYPEERROR = 1;

    private static NSNumberFormatter numberFormatter;

    private PurchaseObserver observer;
    private PurchaseManagerConfig config;

    private AppleTransactionObserver appleObserver;
    private PromotionTransactionObserver startupTransactionObserver;
    private SKProductsRequest productsRequest;
    private NSArray<? extends SKProduct> products;

    private final List<Transaction> restoredTransactions = new ArrayList<Transaction>();

    @Override
    public String storeName() {
        return PurchaseManagerConfig.STORE_NAME_IOS_APPLE;
    }

    /**
     * @param autoFetchInformation is not used, because without product information on ios it's not possible to fill
     *                             {@link Transaction} object on successful purchase
     **/
    @Override
    public void install(PurchaseObserver observer, PurchaseManagerConfig config, boolean
            autoFetchInformation) {
        this.observer = observer;
        this.config = config;

        log(LOGTYPELOG, "Installing purchase observer...");

        // Check if the device is configured for purchases.
        if (SKPaymentQueue.canMakePayments()) {
            // Create string set from offer identifiers.
            int size = config.getOfferCount();
            NSMutableSet<String> productIdentifiers = (NSMutableSet<String>) NSMutableSet.alloc()
                    .initWithCapacity(size);
            for (int i = 0; i < size; i++) {
                productIdentifiers.addObject(config.getOffer(i).getIdentifierForStore
                        (PurchaseManagerConfig.STORE_NAME_IOS_APPLE));
            }

            if (appleObserver == null && startupTransactionObserver == null) {
                // Installing intermediate observer to handle App Store promotions
                startupTransactionObserver = PromotionTransactionObserver.alloc().init();
                final SKPaymentQueue defaultQueue = SKPaymentQueue.defaultQueue();
                defaultQueue.addTransactionObserver(startupTransactionObserver);
                // FixME how to assigne addStrongReference????
                // defaultQueue.addStrongRef(startupTransactionObserver);
                log(LOGTYPELOG, "Startup purchase observer successfully installed!");
            }

            // Request configured offers/products.
            log(LOGTYPELOG, "Requesting products...");
            productsRequest = SKProductsRequest.alloc().initWithProductIdentifiers
                    (productIdentifiers);
            productsRequest.setDelegate(new IosFetchProductsAndInstallDelegate());
            productsRequest.start();
        } else {
            log(LOGTYPEERROR, "Error setting up in-app-billing: Device not configured for " +
                    "purchases!");
            observer.handleInstallError(new RuntimeException("Error installing purchase observer:" +
                    " Device not configured for purchases!"));
        }
    }

    @Override
    public boolean installed() {
        return appleObserver != null;
    }

    @Override
    public void dispose() {
        if (appleObserver != null) {
            // Remove and null our apple transaction observer.
            ((SKPaymentQueue) SKPaymentQueue.defaultQueue()).removeTransactionObserver(appleObserver);
            appleObserver = null;
            productsRequest = null;
            products = null;
            restoredTransactions.clear();

            observer = null;
            config = null;

            log(LOGTYPELOG, "Disposed purchase manager!");
        }
    }

    @Override
    public void purchase(String identifier) {
        // Find the SKProduct for this identifier.
        Offer offer = config.getOffer(identifier);
        String identifierForStore = offer.getIdentifierForStore(PurchaseManagerConfig.STORE_NAME_IOS_APPLE);
        SKProduct product = getProductByStoreIdentifier(identifierForStore);

        if (product == null) {
            // Product with this identifier not found: load product info first and try to purchase again
            log(LOGTYPELOG, "Requesting product info for " + identifierForStore);
            NSMutableSet<String> identifierForStoreSet =
                    (NSMutableSet<String>) NSMutableSet.alloc().initWithCapacity(1);
            identifierForStoreSet.addObject(identifierForStore);
            productsRequest = SKProductsRequest.alloc().initWithProductIdentifiers(identifierForStoreSet);
            productsRequest.setDelegate(new AppleProductsDelegatePurchase());
            productsRequest.start();
        } else {
            // Create a SKPayment from the product and start purchase flow
            log(LOGTYPELOG, "Purchasing product " + identifier + " ...");
            SKPayment payment = SKPayment.paymentWithProduct(product);
            SKPaymentQueue.defaultQueue().addPayment(payment);
        }
    }

    @Override
    public void purchaseRestore() {
        log(LOGTYPELOG, "Restoring purchases...");

        // Clear previously restored transactions.
        restoredTransactions.clear();
        // Start the restore flow.
        ((SKPaymentQueue) SKPaymentQueue.defaultQueue()).restoreCompletedTransactions();
    }

    SKProduct getProductByStoreIdentifier(String identifierForStore) {
        if (products == null)
            return null;
        for (SKProduct product : products) {
            if (product.productIdentifier().equals(identifierForStore)) {
                return product;
            }
        }
        return null;
    }

    /**
     * Returns the original and unique transaction ID of a purchase.
     */
    private String getOriginalTxID(SKPaymentTransaction transaction) {
        if (transaction != null) {
            if (transaction.originalTransaction() != null) {
                // the "original" transaction ID is 'null' for first time purchases but non-'null' for restores (it's unique!)
                return transaction.originalTransaction().transactionIdentifier();
            } else {
                // the regular transaction idetifier. This one changes every time if a product is restored!!
                return transaction.transactionIdentifier();
            }
        } else {
            // transaction object was 'null': we shouldn't generally get here
            return null;
        }
    }

    /**
     * Converts a purchase to our transaction object.
     */
    @Nullable
    Transaction transaction(SKPaymentTransaction t) {
        SKPayment payment = t.payment();
        String productIdentifier = payment.productIdentifier();
        SKProduct product = getProductByStoreIdentifier(productIdentifier);
        if (product == null) {
            // if we didn't request product information -OR- it's not in iTunes, it will be null
            System.err.println("gdx-pay: product not registered/loaded: " + productIdentifier);
        }

        // Build the transaction from the payment transaction object.
        Transaction transaction = new Transaction();

        Offer offerForStore = config.getOfferForStore(PurchaseManagerConfig.STORE_NAME_IOS_APPLE,
                productIdentifier);
        if (offerForStore == null) {
            System.err.println("Product not configured in PurchaseManagerConfig: " +
                    productIdentifier + ", skipping transaction.");
            return null;
        }

        transaction.setIdentifier(offerForStore.getIdentifier());

        transaction.setStoreName(PurchaseManagerConfig.STORE_NAME_IOS_APPLE);
        transaction.setOrderId(getOriginalTxID(t));


        //TODO IG adjust like adviced in the line below
        //transaction.setPurchaseTime(t.getTransactionDate() != null ? t.getTransactionDate().toDate() : new Date());
        transaction.setPurchaseTime(toJavaDate(t.transactionDate()));
        if (product != null) {
            // if we didn't load product information, product will be 'null' (we only set if available)
            transaction.setPurchaseText("Purchased: " + product.localizedTitle());
            transaction.setPurchaseCost((int) Math.round(product.price().doubleValue() * 100));
            NSLocale locale = product.priceLocale();
            transaction.setPurchaseCostCurrency((String) locale.objectForKey(Foundation.NSLocaleCurrencyCode()));
        } else {
            // product information was empty (not loaded or product didn't exist)
            transaction.setPurchaseText("Purchased: " + productIdentifier);
            transaction.setPurchaseCost(0);
            transaction.setPurchaseCostCurrency(null);
        }

        transaction.setReversalTime(null);  // no refunds for iOS!
        transaction.setReversalText(null);

        if (payment.requestData() != null) {
            final String transactionData;
            transactionData = payment.requestData().base64EncodedStringWithOptions(0);
            transaction.setTransactionData(transactionData);
        } else {
            transaction.setTransactionData(null);
        }

        // NOTE: although deprecated as of iOS 7, "transactionReceipt" is still available as of iOS 9 & hopefully long there after :)
        String transactionDataSignature;
        try {
            NSData transactionReceipt = t.transactionReceipt();
            transactionDataSignature = transactionReceipt.base64EncodedStringWithOptions(0);
        } catch (Throwable e) {
            log(LOGTYPELOG, "SKPaymentTransaction.transactionReceipt appears broken (was deprecated starting iOS 7.0).", e);
            transactionDataSignature = null;
        }
        transaction.setTransactionDataSignature(transactionDataSignature);

        // return the transaction
        return transaction;
    }

    private Date toJavaDate(NSDate nsDate) {
        double sinceEpoch = nsDate.timeIntervalSince1970();
        return new Date((long) (sinceEpoch * 1000));
    }

    private class AppleProductsDelegatePurchase implements SKProductsRequestDelegate,
            SKRequestDelegate {

        @Override
        public void productsRequestDidReceiveResponse(SKProductsRequest request,
                                                      SKProductsResponse response) {
            // Received the registered products from AppStore.
            products = response.products();
            if (products.size() == 1) {
                // Create a SKPayment from the product and start purchase flow
                SKProduct product = products.get(0);
                log(LOGTYPELOG, "Product info received/purchasing product " + product
                        .productIdentifier() + " ...");
                SKPayment payment = SKPayment.paymentWithProduct(product);
                ((SKPaymentQueue) SKPaymentQueue.defaultQueue()).addPayment(payment);
            } else {
                // wrong product count returned
                String errorMessage = "Error purchasing product (wrong product info count " +
                        "returned: " + products.size() + ")!";
                log(LOGTYPEERROR, errorMessage);
                observer.handlePurchaseError(new RuntimeException(errorMessage));
            }
        }

        @Override
        public void requestDidFailWithError(SKRequest request, NSError error) {
            String errorMessage = "Error requesting product info to later purchase: " + (error !=
                    null ? error.toString() : "unknown");
            log(LOGTYPEERROR, errorMessage);
            observer.handlePurchaseError(new RuntimeException(errorMessage));
        }
    }

    private class IosFetchProductsAndInstallDelegate implements SKProductsRequestDelegate,
            SKRequestDelegate {

        @Override
        public void productsRequestDidReceiveResponse(SKProductsRequest request,
                                                      SKProductsResponse response) {
            // Received the registered products from AppStore.
            products = response.products();
            log(LOGTYPELOG, products.size() + " products successfully received");

            final SKPaymentQueue defaultQueue = (SKPaymentQueue) SKPaymentQueue.defaultQueue();

            // Create and register our apple transaction observer.
            if (appleObserver == null) {
                if (startupTransactionObserver != null) {
                    defaultQueue.removeTransactionObserver(startupTransactionObserver);
                    startupTransactionObserver = null;
                }

                // Create and register our apple transaction observer.
                appleObserver = AppleTransactionObserver.alloc().init();
                appleObserver.purchaseManageriOSApple = PurchaseManageriOSApple.this;

                defaultQueue.addTransactionObserver(appleObserver);
                log(LOGTYPELOG, "Purchase observer successfully installed!");
            }

            // notify of success...
            observer.handleInstall();

            // complete unfinished transactions
            final NSArray<? extends SKPaymentTransaction> transactions = defaultQueue
                    .transactions();
            log(LOGTYPELOG, "There are " + transactions.size() + " unfinished transactions. Try " +
                    "to finish...");
            appleObserver.paymentQueueUpdatedTransactions(defaultQueue, transactions);
        }

        @Override
        public void requestDidFailWithError(SKRequest request, NSError error) {
            log(LOGTYPEERROR, "Error requesting products: " + (error != null ? error.toString() :
                    "unknown"));
            // Products request failed (likely due to insuficient network connection).
            observer.handleInstallError(new RuntimeException("Error requesting products: " +
                    (error != null ? error.toString() : "unknown")));
        }
    }


    // Transaction Observer for App Store promotions must be in place right after
    // didFinishLaunching(). So this is installed at app start before our full
    // AppleTransactionObserver is ready after fetching product information.
    private class PromotionTransactionObserver extends SKPaymentTransactionObserverAdapter {

        @Override
        public boolean paymentQueueShouldAddStorePaymentForProduct(SKPaymentQueue queue, SKPayment payment, SKProduct product) {
            return shouldProcessPromotionalStorePayment(queue, payment, product);
        }
    }


    void log(final int type, final String message) {
        log(type, message, null);
    }

    void log(final int type, final String message, Throwable e) {
        if (LOGDEBUG) {
            if (type == LOGTYPELOG)
                System.out.println('[' + TAG + "] " + message);
            if (type == LOGTYPEERROR)
                System.err.println('[' + TAG + "] " + message);
            if (e != null)
                System.err.println('[' + TAG + "] " + e);
        }
    }

    @Override
    public Information getInformation(String identifier) {
        if (products != null) {
            for (SKProduct p : products) {
                if (p.productIdentifier().equals(identifier)) {
                    if (numberFormatter == null) {
                        numberFormatter = NSNumberFormatter.alloc().init();
                        numberFormatter.setFormatterBehavior(NSNumberFormatterBehavior
                                .Behavior10_4);
                        numberFormatter.setNumberStyle(NSNumberFormatterStyle.CurrencyStyle);
                    }
                    numberFormatter.setLocale(p.priceLocale());

                    return Information.newBuilder()
                            .localName(p.localizedTitle())
                            .localDescription(p.localizedDescription())
                            .localPricing(numberFormatter.stringFromNumber(p.price()))

                            // p.priceLocale().currencyCode() is not supported on iOS 9
                            .priceCurrencyCode(String.valueOf(p.priceLocale().objectForKey("CurrencyCode")))
                            .priceInCents(MathUtils.ceilPositive(p.price().floatValue() * 100))
                            .priceAsDouble(p.price().doubleValue())
                            .build();

                }
            }
        }
        return Information.UNAVAILABLE;
    }


    private FreeTrialPeriod convertToFreeTrialPeriod(SKProduct product) {
        if (!IosVersion.is_11_2_orAbove()) {
            // introductoryPrice is introduced in ios 11.2
            return null;
        }

        final SKProductDiscount introductoryPrice = product.introductoryPrice();
        if (introductoryPrice == null || introductoryPrice.subscriptionPeriod() == null || introductoryPrice.subscriptionPeriod().numberOfUnits() == 0) {
            return null;
        }

        if (introductoryPrice.price() != null && introductoryPrice.price().doubleValue() > 0D) {
            // in that case, it is not a free trial. We do not yet support reduced price introductory offers.
            return null;
        }

        final SKProductSubscriptionPeriod subscriptionPeriod = introductoryPrice.subscriptionPeriod();
        return new FreeTrialPeriod(
                (int) subscriptionPeriod.numberOfUnits(),
                SKProductPeriodUnitToPeriodUnitConverter.convertToPeriodUnit(subscriptionPeriod.unit())
        );
    }

    @Override
    public String toString() {
        return PurchaseManagerConfig.STORE_NAME_IOS_APPLE;
    }


    /**
     * Override this method in an own subclass if you need to change the default behaviour for promotional
     * App Store payments. The default behaviour adds the store payment to the payment queue and processes
     * it as soon as the product information is available.
     */
    @SuppressWarnings("WeakerAccess")
    protected boolean shouldProcessPromotionalStorePayment(SKPaymentQueue queue, SKPayment payment, SKProduct product) {
        return true;
    }


    @Override
    public void paymentQueueUpdatedTransactions(SKPaymentQueue queue, NSArray<? extends SKPaymentTransaction> transactions) {
        for (final SKPaymentTransaction transaction : transactions) {
            long state = transaction.transactionState();
            switch ((int) state) {
                // Product was successfully purchased.
                case (int) SKPaymentTransactionState.Purchased:
                    // Parse transaction data.
                    final Transaction t = transaction(transaction);
                    if (t == null) {
                        break;
                    }

                    if (t.getTransactionDataSignature() == null) {
                        NSURL receiptURL = NSBundle.mainBundle().appStoreReceiptURL();
                        NSData receipt = NSData.dataWithContentsOfURL(receiptURL);
                        if (receipt == null) {
                            log(LOGTYPELOG, "Fetching receipt...");
                            final SKReceiptRefreshRequest request = SKReceiptRefreshRequest.alloc();
                            request.setDelegate(new SKRequestDelegate() {

                                public void didFinish (SKRequest r) {
                                    // Receipt refresh request finished.

                                    if (r.equals(request)) {
                                        NSURL receiptURL = NSBundle.mainBundle().appStoreReceiptURL();
                                        NSData receipt = NSData.dataWithContentsOfURL(receiptURL);
                                        String encodedReceipt = receipt.base64EncodedStringWithOptions(0);
                                        //NSDataBase64EncodingOptions.None
                                        // FIXME: parse out actual receipt for this IAP purchase:
                                        //                      t.setTransactionDataSignature(encodedReceipt);
                                        log(LOGTYPELOG, "Receipt was fetched!");
                                    } else {
                                        log(LOGTYPEERROR, "Receipt fetching failed: Request doesn't equal initial request!");
                                    }

                                    log(LOGTYPELOG, "Transaction was completed: " + getOriginalTxID(transaction));
                                    observer.handlePurchase(t);

                                    // Finish transaction.
                                    SKPaymentQueue.defaultQueue().finishTransaction(transaction);
                                }

                                public void didFail (SKRequest request, NSError error) {
                                    // Receipt refresh request failed. Let's just continue.
                                    log(LOGTYPEERROR, "Receipt fetching failed: " + error.toString());
                                    log(LOGTYPELOG, "Transaction was completed: " + getOriginalTxID(transaction));
                                    observer.handlePurchase(t);

                                    // Finish transaction.
                                    SKPaymentQueue.defaultQueue().finishTransaction(transaction);
                                }
                            });
                            request.start();
                        } else {
                            String encodedReceipt = receipt.base64EncodedStringWithOptions(0);
                            // NSDataBase64EncodingOptions.None == 0
                            // FIXME: parse out actual receipt for this IAP purchase:
                            //                  t.setTransactionDataSignature(encodedReceipt);

                            log(LOGTYPELOG, "Transaction was completed: " + getOriginalTxID(transaction));
                            observer.handlePurchase(t);

                            // Finish transaction.
                            SKPaymentQueue.defaultQueue().finishTransaction(transaction);
                        }
                    }
                    else {
                        // we are done: let's report!
                        log(LOGTYPELOG, "Transaction was completed: " + getOriginalTxID(transaction));
                        observer.handlePurchase(t);

                        // Finish transaction.
                        SKPaymentQueue.defaultQueue().finishTransaction(transaction);
                    }
                    break;
                case (int)  SKPaymentTransactionState.Failed:
                    // Purchase failed.

                    // Decide if user cancelled or transaction failed.
                    NSError error = transaction.error();
                    if (error == null) {
                        log(LOGTYPEERROR, "Transaction failed but error-object is null: " + transaction);
                        observer.handlePurchaseError(new GdxPayException("Transaction failed: " + transaction));
                    }
                    else if (error.code() == SKErrorCode.PaymentCancelled) {
                        log(LOGTYPEERROR, "Transaction was cancelled by user!");
                        observer.handlePurchaseCanceled();
                    } else {
                        log(LOGTYPEERROR, "Transaction failed: " + error.toString());
                        observer.handlePurchaseError(new GdxPayException("Transaction failed: " + error.localizedDescription()));
                    }

                    // Finish transaction.
                    SKPaymentQueue.defaultQueue().finishTransaction(transaction);
                    break;
                case (int)  SKPaymentTransactionState.Restored:
                    // A product has been restored.

                    // Parse transaction data.
                    Transaction ta = transaction(transaction);
                    if (ta == null)
                        break;

                    restoredTransactions.add(ta);

                    // Finish transaction.
                    SKPaymentQueue.defaultQueue().finishTransaction(transaction);

                    log(LOGTYPELOG, "Transaction has been restored: " + getOriginalTxID(transaction));
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public void paymentQueueRestoreCompletedTransactionsFinished (SKPaymentQueue queue) {
        // All products have been restored.
        log(LOGTYPELOG, "All transactions have been restored!");
        observer.handleRestore(restoredTransactions.toArray(new Transaction[restoredTransactions.size()]));
        restoredTransactions.clear();
    }


    public void paymentQueueRestoreCompletedTransactionsFailedWithError (SKPaymentQueue queue, NSError error) {
        // Restoration failed.

        // Decide if user cancelled or transaction failed.
        if (error.code() == SKErrorCode.PaymentCancelled) {
            log(LOGTYPEERROR, "Restoring of transactions was cancelled by user!");
            observer.handleRestoreError(new GdxPayException("Restoring of purchases was cancelled by user!"));
        } else {
            log(LOGTYPEERROR, "Restoring of transactions failed: " + error.toString());
            observer.handleRestoreError(new GdxPayException("Restoring of purchases failed: " + error.localizedDescription()));
        }
    }

}
