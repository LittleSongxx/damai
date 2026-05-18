package com.damai.service;

import com.alibaba.fastjson.JSON;
import com.damai.core.RedisKeyManage;
import com.damai.dto.ProgramOrderCreateDto;
import com.damai.enums.BaseCode;
import com.damai.exception.DaMaiFrameException;
import com.damai.redis.RedisCache;
import com.damai.redis.RedisKeyBuild;
import com.damai.service.domain.ProgramOrderIdempotencyRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
public class ProgramOrderIdempotencyService {

    private static final long TTL_MINUTES = 10L;

    @Autowired
    private RedisCache redisCache;

    public String execute(String idempotencyKey, ProgramOrderCreateDto programOrderCreateDto, Supplier<String> supplier) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return supplier.get();
        }
        String requestHash = DigestUtils.md5DigestAsHex(
                JSON.toJSONString(programOrderCreateDto).getBytes(StandardCharsets.UTF_8));
        RedisKeyBuild redisKey = RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_ORDER_IDEMPOTENCY, idempotencyKey);
        ProgramOrderIdempotencyRecord cached = redisCache.get(redisKey, ProgramOrderIdempotencyRecord.class);
        if (cached != null) {
            return resolveCached(idempotencyKey, requestHash, cached);
        }

        ProgramOrderIdempotencyRecord processing = buildRecord(idempotencyKey, requestHash, "PROCESSING", null, "订单创建中");
        Boolean locked = (Boolean) redisCache.getInstance()
                .opsForValue()
                .setIfAbsent(redisKey.getRelKey(), JSON.toJSONString(processing), TTL_MINUTES, TimeUnit.MINUTES);
        if (!Boolean.TRUE.equals(locked)) {
            ProgramOrderIdempotencyRecord current = redisCache.get(redisKey, ProgramOrderIdempotencyRecord.class);
            if (current != null) {
                return resolveCached(idempotencyKey, requestHash, current);
            }
            throw new DaMaiFrameException(BaseCode.OPERATION_IS_TOO_FREQUENT_PLEASE_TRY_AGAIN_LATER.getCode(), "订单正在处理中，请稍后再试");
        }

        try {
            String orderNumber = supplier.get();
            ProgramOrderIdempotencyRecord success = buildRecord(idempotencyKey, requestHash, "SUCCESS", orderNumber, "订单已创建");
            redisCache.set(redisKey, success, TTL_MINUTES, TimeUnit.MINUTES);
            return orderNumber;
        } catch (RuntimeException ex) {
            redisCache.del(redisKey);
            throw ex;
        }
    }

    private String resolveCached(String idempotencyKey, String requestHash, ProgramOrderIdempotencyRecord cached) {
        if (!Objects.equals(requestHash, cached.getRequestHash())) {
            log.warn("program order idempotency hash conflict, key={}", idempotencyKey);
            throw new DaMaiFrameException(BaseCode.OPERATION_IS_TOO_FREQUENT_PLEASE_TRY_AGAIN_LATER.getCode(),
                    "幂等键已被其他请求占用，请更换请求后重试");
        }
        if ("SUCCESS".equals(cached.getStatus()) && StringUtils.hasText(cached.getOrderNumber())) {
            return cached.getOrderNumber();
        }
        throw new DaMaiFrameException(BaseCode.OPERATION_IS_TOO_FREQUENT_PLEASE_TRY_AGAIN_LATER.getCode(),
                "订单正在处理中，请稍后查看结果");
    }

    private ProgramOrderIdempotencyRecord buildRecord(String idempotencyKey,
                                                      String requestHash,
                                                      String status,
                                                      String orderNumber,
                                                      String message) {
        ProgramOrderIdempotencyRecord record = new ProgramOrderIdempotencyRecord();
        record.setIdempotencyKey(idempotencyKey);
        record.setRequestHash(requestHash);
        record.setStatus(status);
        record.setOrderNumber(orderNumber);
        record.setMessage(message);
        record.setUpdatedAt(new Date());
        return record;
    }
}
