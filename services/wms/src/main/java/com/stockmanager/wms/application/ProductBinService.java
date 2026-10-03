package com.stockmanager.wms.application;

import com.stockmanager.wms.domain.Locations;
import com.stockmanager.wms.domain.PutawayLine;
import com.stockmanager.wms.infrastructure.ProductBinRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
public class ProductBinService {

    private final ProductBinRepository productBinRepository;

    ProductBinService(ProductBinRepository productBinRepository) {
        this.productBinRepository = productBinRepository;
    }

    public void assign(long productId, String binCode) {
        try {
            productBinRepository.assign(Locations.CENTER, productId, binCode);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("이미 다른 상품이 쓰는 칸입니다: " + binCode);
        }
    }

    public <T> List<PutawayLine> guide(String locationCode, List<T> lines,
                        Function<T, Long> productIdOf, Function<T, Integer> quantityOf) {
        Map<Long, String> bins = productBinRepository.findAll(locationCode);
        return lines.stream()
            .map(line -> {
                long productId = productIdOf.apply(line);
                return new PutawayLine(productId, quantityOf.apply(line), bins.get(productId));
            })
            .toList();
    }

    public void requireEveryBinAssigned(List<PutawayLine> guided) {
        List<Long> missing = guided.stream()
            .filter(line -> line.binCode() == null)
            .map(PutawayLine::productId)
            .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("칸이 지정되지 않은 상품이 있습니다: 상품 " + missing);
        }
    }
}
