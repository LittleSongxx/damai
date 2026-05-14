package org.javaup.ai.ai.function.call;

import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson.JSON;
import org.javaup.ai.enums.BaseCode;
import org.javaup.ai.vo.TicketUserVo;
import org.javaup.ai.vo.UserDetailVo;
import org.javaup.ai.vo.result.TicketUserResultVo;
import org.javaup.ai.vo.result.UserDetailResultVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.javaup.ai.constants.DaMaiConstant.TICKET_USER_LIST_URL;
import static org.javaup.ai.constants.DaMaiConstant.CURRENT_USER_URL;
import static org.javaup.ai.constants.DaMaiConstant.USER_DETAIL_URL;

/**
 * @program: 大麦-ai智能服务项目。 添加 阿星不是程序员 微信，添加时备注 ai 来获取项目的完整资料 
 * @description: 用户服务类
 * @author: 阿星不是程序员
 **/
@Component
public class UserCall {

    @Autowired
    private DaMaiRequestAuthSupport requestAuthSupport;
    
    public UserDetailVo userDetail(String mobile){
        Map<String,String> params = new HashMap<>(2);
        params.put("mobile", mobile);
        UserDetailResultVo userDetailResultVo = new UserDetailResultVo();
        String result = requestAuthSupport.apply(HttpRequest.post(userDetailUrl()))
                .body(JSON.toJSONString(params))
                .timeout(20000)
                .execute().body();
        userDetailResultVo = JSON.parseObject(result, UserDetailResultVo.class);
        if (!Objects.equals(userDetailResultVo.getCode(), BaseCode.SUCCESS.getCode())) {
            throw new RuntimeException("调用大麦系统查询用户信息失败");
        }
        return userDetailResultVo.getData();
    }

    public UserDetailVo currentUser(String token) {
        UserDetailResultVo userDetailResultVo;
        String result = requestAuthSupport.apply(HttpRequest.post(currentUserUrl()))
                .header("token", token)
                .header("code", currentUserCode())
                .timeout(20000)
                .execute().body();
        userDetailResultVo = JSON.parseObject(result, UserDetailResultVo.class);
        if (!Objects.equals(userDetailResultVo.getCode(), BaseCode.SUCCESS.getCode())) {
            throw new RuntimeException("调用大麦系统获取当前用户失败");
        }
        return userDetailResultVo.getData();
    }
    
    public List<TicketUserVo> ticketUserList(Long userId){
        Map<String,Object> params = new HashMap<>(2);
        params.put("userId", userId);
        TicketUserResultVo ticketUserResultVo = new TicketUserResultVo();
        String result = requestAuthSupport.apply(HttpRequest.post(ticketUserListUrl()))
                .body(JSON.toJSONString(params))
                .timeout(20000)
                .execute().body();
        ticketUserResultVo = JSON.parseObject(result, TicketUserResultVo.class);
        if (!Objects.equals(ticketUserResultVo.getCode(), BaseCode.SUCCESS.getCode())) {
            throw new RuntimeException("调用大麦系统查询购票人信息失败");
        }
        if (Objects.isNull(ticketUserResultVo.getData())) {
            throw new RuntimeException("购票人信息不存在");
        }
        return ticketUserResultVo.getData();
    }

    protected String userDetailUrl() {
        return USER_DETAIL_URL;
    }

    protected String currentUserUrl() {
        return CURRENT_USER_URL;
    }

    protected String ticketUserListUrl() {
        return TICKET_USER_LIST_URL;
    }

    protected String currentUserCode() {
        String code = System.getenv("DAMAI_AI_CHANNEL_CODE");
        return (code == null || code.isBlank()) ? "0001" : code;
    }
}
