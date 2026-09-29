package com.stockmanager.wms.api;

import com.stockmanager.wms.application.TransferService;
import com.stockmanager.wms.domain.Transfer;
import com.stockmanager.wms.domain.TransferLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
class TransferController {

    private final TransferService transferService;

    TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/transfers")
    TransferResponse request(@Valid @RequestBody TransferRequest request) {
        List<TransferLine> lines = request.items().stream()
            .map(item -> new TransferLine(item.productId(), item.quantity()))
            .toList();
        return TransferResponse.from(
            transferService.request(request.fromLocationCode(), request.toLocationCode(), lines)
        );
    }

    @GetMapping("/transfers/{transferId}")
    TransferResponse find(@PathVariable long transferId) {
        return TransferResponse.from(transferService.find(transferId));
    }

    record TransferRequest(@NotBlank String fromLocationCode, @NotBlank String toLocationCode,
                           @NotEmpty @Valid List<Item> items){
        record Item(@NotNull Long productId, @NotNull @Positive Integer quantity) {
        }
    }

    record TransferResponse(long transferId, String fromLocationCode, String toLocationCode,String status,
                            List<Item> items){
        record Item(long productId, int quantity) {
        }

        static TransferResponse from(Transfer transfer) {
            return new TransferResponse(transfer.id(), transfer.fromLocationCode(), transfer.toLocationCode(),
                transfer.status().name(),
                transfer.lines().stream().map(line -> new Item(line.productId(), line.quantity())).toList());

        }
    }
}
