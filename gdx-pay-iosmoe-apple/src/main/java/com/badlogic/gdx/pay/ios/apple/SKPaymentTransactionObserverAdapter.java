package com.badlogic.gdx.pay.ios.apple;

/*
 * Copyright (C) 2013-2015 RoboVM AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.moe.natj.general.Pointer;
import org.moe.natj.general.ann.Owned;
import org.moe.natj.objc.ann.NotImplemented;
import org.moe.natj.objc.ann.Selector;

import apple.NSObject;
import apple.foundation.NSArray;
import apple.foundation.NSError;
import apple.storekit.SKPayment;
import apple.storekit.SKPaymentQueue;
import apple.storekit.SKPaymentTransaction;
import apple.storekit.SKProduct;
import apple.storekit.protocol.SKPaymentTransactionObserver;

public class SKPaymentTransactionObserverAdapter extends NSObject implements SKPaymentTransactionObserver {

    protected SKPaymentTransactionObserverAdapter(Pointer peer) {
        super(peer);
    }

    @Owned
    @Selector("alloc")
    public static native SKPaymentTransactionObserverAdapter alloc();

    @Selector("init")
    public native SKPaymentTransactionObserverAdapter init();

    @Override
    @NotImplemented
    public void paymentQueueRemovedTransactions(SKPaymentQueue queue, NSArray<? extends SKPaymentTransaction> transactions) {
    }

    @Override
    @NotImplemented
    public void paymentQueueRestoreCompletedTransactionsFailedWithError(SKPaymentQueue queue, NSError error) {
    }


    @Override
    @NotImplemented
    public void paymentQueueUpdatedTransactions(SKPaymentQueue queue, NSArray<? extends SKPaymentTransaction> transactions) {
    }

    @Override
    @NotImplemented
    public void paymentQueueRestoreCompletedTransactionsFinished(SKPaymentQueue queue) {
    }

    @Override
    @NotImplemented
    public boolean paymentQueueShouldAddStorePaymentForProduct(SKPaymentQueue queue, SKPayment payment, SKProduct product) {
        return false;
    }

    @Override
    @NotImplemented
    public void paymentQueueDidRevokeEntitlementsForProductIdentifiers(SKPaymentQueue queue, NSArray<String> productIdentifiers) {
    }
}
