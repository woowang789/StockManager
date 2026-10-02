package com.stockmanager.wms.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public final class ProductLines {

    private ProductLines() {
    }

    public static <T> void requireDistinct(List<T> lines, Function<T, Long> productIdOf) {
        Set<Long> seen = new HashSet<>();
        for (T line : lines) {
            long productId = productIdOf.apply(line);
            if (!seen.add(productId)) {
                throw new IllegalArgumentException("같은 상품이 두 줄로 들어 있습니다: 상품 " + productId);
            }
        }
    }
}
