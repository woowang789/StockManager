package com.stockmanager.inventory.domain;

import java.util.List;

public record ReserveCommand(String refType, String refId, String locationCode, List<Item> items) {

    public ReserveCommand {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("예약할 항목이 없습니다.");
        }
    }

    public record Item(long productId, int quantity){
        public Item {
            if( quantity <= 0) {
                throw new IllegalArgumentException("예약 수량은 1개 이상이어야 한다.");
            }
        }
    }

}
