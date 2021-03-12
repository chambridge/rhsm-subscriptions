/*
 * Copyright (c) 2021 Red Hat, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Red Hat trademarks are not licensed under GPLv3. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.subscriptions.subscription;

import static org.junit.jupiter.api.Assertions.*;

import org.candlepin.subscriptions.subscription.api.model.SubscriptionProduct;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

class SubscriptionDtoUtilTest {
    @Test
    void testExtractSku() {
        var dto = new org.candlepin.subscriptions.subscription.api.model.Subscription();
        SubscriptionProduct product = new SubscriptionProduct().parentSubscriptionProductId(null)
            .sku("testSku");
        SubscriptionProduct childSku = new SubscriptionProduct().parentSubscriptionProductId(123)
            .sku("childSku");
        List<SubscriptionProduct> products = Arrays.asList(product, childSku);
        dto.setSubscriptionProducts(products);

        assertEquals("testSku", SubscriptionDtoUtil.extractSku(dto));
    }

    @Test
    void testExtractSkuFailsWithImproperSubscription() {
        var dto = new org.candlepin.subscriptions.subscription.api.model.Subscription();
        SubscriptionProduct product = new SubscriptionProduct().parentSubscriptionProductId(null)
            .sku("testSku");
        SubscriptionProduct childSku = new SubscriptionProduct().parentSubscriptionProductId(null)
            .sku("childSku");
        List<SubscriptionProduct> products = Arrays.asList(product, childSku);
        dto.setSubscriptionProducts(products);

        assertThrows(IllegalStateException.class, () -> SubscriptionDtoUtil.extractSku(dto));
    }
}
