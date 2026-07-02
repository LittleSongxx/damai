package org.javaup.ai.service;

import org.javaup.ai.dto.CustomerQuickAnswerRequest;
import org.javaup.ai.enums.CustomerServiceIntent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CustomerIntentResolver {

    public CustomerServiceIntent resolve(CustomerQuickAnswerRequest request, String message) {
        if (request != null && StringUtils.hasText(request.getIntentHint())) {
            return CustomerServiceIntent.from(request.getIntentHint());
        }
        String text = message == null ? "" : message;
        if (containsAny(text, "投诉", "12315", "315", "律师", "曝光", "报警", "欺诈")) {
            return CustomerServiceIntent.COMPLAINT;
        }
        if (containsAny(text, "人工", "客服", "转人", "真人")) {
            return CustomerServiceIntent.HUMAN_HANDOFF;
        }
        if (containsAny(text, "退票", "退款", "退订")) {
            return CustomerServiceIntent.REFUND_RULE;
        }
        if (containsAny(text, "实名", "身份证", "证件")) {
            return CustomerServiceIntent.REAL_NAME_RULE;
        }
        if (containsAny(text, "入场", "进场", "验票", "安检")) {
            return CustomerServiceIntent.ENTRY_RULE;
        }
        if (containsAny(text, "票档", "座位", "价格", "余票")) {
            return CustomerServiceIntent.TICKET_CATEGORY;
        }
        if (containsAny(text, "订单", "售后", "改地址", "纸质票", "电子票")) {
            return CustomerServiceIntent.ORDER_AFTERSALE;
        }
        if (containsAny(text, "发票", "抬头")) {
            return CustomerServiceIntent.INVOICE;
        }
        if (containsAny(text, "演出", "场次", "节目", "歌手", "脱口秀")) {
            return CustomerServiceIntent.EVENT_SEARCH;
        }
        return CustomerServiceIntent.GENERAL_CHAT;
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
