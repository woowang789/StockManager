package com.stockmanager.wms.application;

import com.stockmanager.common.web.ConcurrentUpdateException;
import com.stockmanager.wms.domain.InboundLine;
import com.stockmanager.wms.domain.InboundOrigin;
import com.stockmanager.wms.domain.InspectionLine;
import com.stockmanager.wms.domain.Locations;
import com.stockmanager.wms.domain.ProductLines;
import com.stockmanager.wms.domain.Transfer;
import com.stockmanager.wms.domain.TransferLine;
import com.stockmanager.wms.domain.TransferStatus;
import com.stockmanager.wms.infrastructure.InboundRepository;
import com.stockmanager.wms.infrastructure.InventoryClient;
import com.stockmanager.wms.infrastructure.TransferEventRecorder;
import com.stockmanager.wms.infrastructure.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private static final String REF_TYPE = "TRANSFER";

    private final TransferRepository transferRepository;
    private final InventoryClient inventoryClient;
    private final InboundRepository inboundRepository;
    private final TransferEventRecorder transferEventRecorder;
    private final TransactionTemplate transactionTemplate;

    TransferService(TransferRepository transferRepository, InventoryClient inventoryClient,
                    InboundRepository inboundRepository, TransferEventRecorder transferEventRecorder,
                    TransactionTemplate transactionTemplate) {
        this.transferRepository = transferRepository;
        this.inventoryClient = inventoryClient;
        this.inboundRepository = inboundRepository;
        this.transferEventRecorder = transferEventRecorder;
        this.transactionTemplate = transactionTemplate;
    }

    public Transfer request(String fromLocationCode, String toLocationCode, List<TransferLine> lines) {
        if (fromLocationCode.equals(toLocationCode)) {
            throw new IllegalArgumentException("같은 거점으로는 이동할 수 없습니다: " + fromLocationCode);
        }
        ProductLines.requireDistinct(lines, TransferLine::productId);
        long transferId = transactionTemplate.execute(
            status -> transferRepository.insert(fromLocationCode, toLocationCode, lines));
        return reserve(find(transferId));
    }

    public void confirmPending(Instant createdBefore) {
        for (long transferId : transferRepository.findPendingBefore(createdBefore)) {
            reserve(find(transferId));
        }
    }

    public Transfer pick(long transferId) {
        return advance(transferId, TransferStatus.REQUESTED, TransferStatus.PICKED);
    }

    public Transfer dispatch(long transferId) {
        return transactionTemplate.execute(status -> {
            int changed = transferRepository.changeStatus(
                transferId, TransferStatus.PICKED, TransferStatus.IN_TRANSIT);
            Transfer transfer = find(transferId);
            if (changed == 0) {
                if (transfer.status() != TransferStatus.IN_TRANSIT) {
                    throw new IllegalArgumentException(
                        "이동할 수 없는 상태입니다: " + transferId + " (" + transfer.status() + ")");
                }
                return transfer;
            }
            inboundRepository.insert(
                transfer.toLocationCode(), new InboundOrigin.Transfer(transferId), toExpectedLines(transfer));
            transferEventRecorder.transferDispatched(transfer);
            return transfer;
        });
    }

    public Transfer cancelForShortage(long transferId, List<TransferLine> shortages) {
        return transactionTemplate.execute(status -> {
            Transfer transfer = find(transferId);
            requireShortagesWithin(transfer, shortages);
            cancelOrFail(transfer, shortages);
            return find(transferId);
        });
    }

    private void requireShortagesWithin(Transfer transfer, List<TransferLine> shortages) {
        ProductLines.requireDistinct(shortages, TransferLine::productId);
        Map<Long, Integer> moving = transfer.lines().stream()
            .collect(Collectors.toMap(TransferLine::productId, TransferLine::quantity));
        shortages.forEach(shortage -> {
            Integer quantity = moving.get(shortage.productId());
            if (quantity == null) {
                throw new IllegalArgumentException("이동에 없는 상품은 결품으로 보고할 수 없습니다: "
                    + transfer.id() + ", 상품 " + shortage.productId() + " (이동 품목 " + moving.keySet() + ")");
            }
            if (shortage.quantity() > quantity) {
                throw new IllegalArgumentException("결품이 이동 수량보다 많습니다: "
                    + transfer.id() + ", 상품 " + shortage.productId()
                    + " (결품 " + shortage.quantity() + ", 이동 " + quantity + ")");
            }
        });
    }

    public void receive(long transferId, List<InspectionLine> results, boolean putawayPending) {
        if (transferRepository.changeStatus(transferId, TransferStatus.IN_TRANSIT, TransferStatus.RECEIVED) == 0) {
            throw new IllegalStateException("이동 중이 아닌 이동의 입고 문서입니다: " + transferId);
        }
        transferEventRecorder.transferReceived(find(transferId), results, putawayPending);
    }

    public Transfer cancel(long transferId) {
        return transactionTemplate.execute(status -> {
            Transfer transfer = find(transferId);
            if (transfer.status() == TransferStatus.CANCELED) {
                return transfer;
            }
            cancelOrFail(transfer, List.of());
            return find(transferId);
        });
    }

    private void cancelOrFail(Transfer transfer, List<TransferLine> shortages) {
        TransferStatus from = transfer.status();
        if (from != TransferStatus.REQUESTED && from != TransferStatus.PICKED) {
            throw new IllegalArgumentException("취소할 수 없는 상태입니다: " + transfer.id() + " (" + from + ")");
        }
        if (transferRepository.changeStatus(transfer.id(), from, TransferStatus.CANCELED) == 0) {
            throw new ConcurrentUpdateException(
                "취소하는 사이에 상태가 바뀌었습니다. 다시 시도해 주세요: " + transfer.id() + " (" + from + ")");
        }
        boolean putawayPending = from == TransferStatus.PICKED && Locations.managesBins(transfer.fromLocationCode());
        transferEventRecorder.transferCanceled(transfer, shortages, putawayPending);
    }

    private Transfer advance(long transferId, TransferStatus from, TransferStatus to) {
        return transactionTemplate.execute(status -> {
            int changed = transferRepository.changeStatus(transferId, from, to);
            Transfer transfer = find(transferId);
            if (changed == 0 && transfer.status() != to) {
                throw new IllegalArgumentException(
                    to + "로 보낼 수 없는 상태입니다: " + transferId + " (" + transfer.status() + ")");
            }
            return transfer;
        });
    }

    private List<InboundLine> toExpectedLines(Transfer transfer) {
        return transfer.lines().stream()
            .map(line -> InboundLine.expected(line.productId(), line.quantity()))
            .toList();
    }

    public Transfer find(long transferId) {
        return transferRepository.find(transferId)
            .orElseThrow(() -> new IllegalArgumentException("이동 요청이 없습니다: " + transferId));
    }

    private Transfer reserve(Transfer transfer) {
        Optional<TransferStatus> result = requestReservation(transfer);
        if (result.isEmpty()) {
            return transfer;
        }
        return confirm(transfer, result.get());
    }

    private Optional<TransferStatus> requestReservation(Transfer transfer) {
        try {
            inventoryClient.reserve(toReservationRequest(transfer));
            return Optional.of(TransferStatus.REQUESTED);
        } catch (HttpClientErrorException.Conflict exception) {
            return Optional.of(TransferStatus.REJECTED);
        } catch (RestClientException exception) {
            log.warn("이동 {}의 예약 결과를 모릅니다. PENDING으로 두고 다시 시도합니다: {}",
                transfer.id(), exception.toString());
            return Optional.empty();
        }
    }

    private Transfer confirm(Transfer transfer, TransferStatus result) {
        transferRepository.changeStatus(transfer.id(), TransferStatus.PENDING, result);
        return find(transfer.id());
    }

    private InventoryClient.ReservationRequest toReservationRequest(Transfer transfer) {
        List<InventoryClient.ReservationRequest.Item> items = transfer.lines().stream()
            .map(line -> new InventoryClient.ReservationRequest.Item(line.productId(), line.quantity()))
            .toList();
        return new InventoryClient.ReservationRequest(
            REF_TYPE, String.valueOf(transfer.id()), transfer.fromLocationCode(), items);
    }
}
