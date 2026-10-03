package com.stockmanager.inventory.api;

import com.stockmanager.inventory.application.PosDeductionService;
import com.stockmanager.inventory.domain.PosDeductionCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 판매(영수증)는 sales의 문서다. inventory는 그 판매만큼 가용에서 뺄 뿐이라 이름이 차감이다
@RestController
@RequestMapping("/pos-deductions")
class PosDeductionController {

    private final PosDeductionService posDeductionService;

    PosDeductionController(PosDeductionService posDeductionService) {
        this.posDeductionService = posDeductionService;
    }

    @PostMapping
    PosDeductionResponse deduct(
        @Valid @RequestBody PosDeductionRequest request,
        @RequestHeader(value = "X-User-Id", defaultValue = "unknown") String actor) {

        List<PosDeductionCommand.Item> items = request.items().stream()
            .map(item -> new PosDeductionCommand.Item(item.productId(), item.quantity()))
            .toList();

        PosDeductionCommand command = new PosDeductionCommand(
            request.refType(), request.refId(), request.locationCode(), items);
        return new PosDeductionResponse(posDeductionService.deduct(command, actor));
    }

    // 이 엔드포인트만 쓰는 모양이라 여기에 둔다. 다른 컨트롤러가 함께 쓰게 되면 파일로 꺼낸다
    record PosDeductionRequest(@NotBlank String refType, @NotBlank String refId, @NotBlank String locationCode,
                          @NotEmpty @Valid List<Item> items) {

        record Item(@NotNull Long productId, @NotNull @Positive Integer quantity) {
        }
    }

    record PosDeductionResponse(long movementId) {
    }
}
