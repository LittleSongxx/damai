package com.damai.controller;

import com.damai.common.ApiResponse;
import com.damai.dto.ProgramOrderCreateDto;
import com.damai.enums.ProgramOrderVersion;
import com.damai.service.ProgramOrderIdempotencyService;
import com.damai.service.strategy.ProgramOrderContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @program: 极度真实还原大麦网高并发实战项目。 添加 阿星不是程序员 微信，添加时备注 大麦 来获取项目的完整资料 
 * @description: 节目订单 控制层
 * @author: 阿星不是程序员
 **/
@RestController
@RequestMapping("/program/order")
@Tag(name = "program-order", description = "节目订单")
public class ProgramOrderController {
    
    @Autowired
    private ProgramOrderContext programOrderContext;

    @Autowired
    private ProgramOrderIdempotencyService programOrderIdempotencyService;
    
    @Operation(summary  = "购票V1")
    @PostMapping(value = "/create/v1")
    public ApiResponse<String> createV1(@Valid @RequestBody ProgramOrderCreateDto programOrderCreateDto,
                                        @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return create(programOrderCreateDto, ProgramOrderVersion.V1_VERSION, idempotencyKey);
    }
    
    @Operation(summary  = "购票V2")
    @PostMapping(value = "/create/v2")
    public ApiResponse<String> createV2(@Valid @RequestBody ProgramOrderCreateDto programOrderCreateDto,
                                        @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return create(programOrderCreateDto, ProgramOrderVersion.V2_VERSION, idempotencyKey);
    }
    
    @Operation(summary  = "购票V21")
    @PostMapping(value = "/create/v21")
    public ApiResponse<String> createV21(@Valid @RequestBody ProgramOrderCreateDto programOrderCreateDto,
                                         @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return create(programOrderCreateDto, ProgramOrderVersion.V21_VERSION, idempotencyKey);
    }
    
    @Operation(summary  = "购票V3")
    @PostMapping(value = "/create/v3")
    public ApiResponse<String> createV3(@Valid @RequestBody ProgramOrderCreateDto programOrderCreateDto,
                                        @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return create(programOrderCreateDto, ProgramOrderVersion.V3_VERSION, idempotencyKey);
    }
    
    @Operation(summary  = "购票V31")
    @PostMapping(value = "/create/v31")
    public ApiResponse<String> createV31(@Valid @RequestBody ProgramOrderCreateDto programOrderCreateDto,
                                         @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return create(programOrderCreateDto, ProgramOrderVersion.V31_VERSION, idempotencyKey);
    }
    
    @Operation(summary  = "购票V4")
    @PostMapping(value = "/create/v4")
    public ApiResponse<String> createV4(@Valid @RequestBody ProgramOrderCreateDto programOrderCreateDto,
                                        @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return create(programOrderCreateDto, ProgramOrderVersion.V4_VERSION, idempotencyKey);
    }
    
    @Operation(summary  = "购票V4")
    @PostMapping(value = "/create/v41")
    public ApiResponse<String> createV41(@Valid @RequestBody ProgramOrderCreateDto programOrderCreateDto,
                                         @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return create(programOrderCreateDto, ProgramOrderVersion.V41_VERSION, idempotencyKey);
    }

    private ApiResponse<String> create(ProgramOrderCreateDto programOrderCreateDto,
                                       ProgramOrderVersion version,
                                       String idempotencyKey) {
        String orderNumber = programOrderIdempotencyService.execute(idempotencyKey, programOrderCreateDto,
                () -> programOrderContext.get(version.getVersion()).createOrder(programOrderCreateDto));
        return ApiResponse.ok(orderNumber);
    }
}
