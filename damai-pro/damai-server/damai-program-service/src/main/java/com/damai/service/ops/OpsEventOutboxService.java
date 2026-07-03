package com.damai.service.ops;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baidu.fsg.uid.UidGenerator;
import com.damai.domain.OpsEvent;
import com.damai.entity.OpsEventOutbox;
import com.damai.mapper.OpsEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
@RequiredArgsConstructor
public class OpsEventOutboxService {

    private static final String PENDING = "PENDING";

    private final OpsEventOutboxMapper mapper;
    private final UidGenerator uidGenerator;

    public void enqueue(String routingKey, OpsEvent event) {
        if (event == null) {
            return;
        }
        if (mapper.selectCount(Wrappers.lambdaQuery(OpsEventOutbox.class)
                .eq(OpsEventOutbox::getEventId, event.getEventId())
                .eq(OpsEventOutbox::getStatus, 1)) > 0) {
            return;
        }
        OpsEventOutbox row = new OpsEventOutbox();
        row.setId(uidGenerator.getUid());
        row.setEventId(event.getEventId());
        row.setRoutingKey(routingKey);
        row.setEventType(event.getEventType());
        row.setEventJson(JSON.toJSONString(event));
        row.setPublishStatus(PENDING);
        row.setRetryCount(0);
        row.setNextRetryAt(new Date());
        row.setCreateTime(new Date());
        row.setEditTime(new Date());
        row.setStatus(1);
        mapper.insert(row);
    }
}
