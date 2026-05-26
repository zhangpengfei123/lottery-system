package cn.bugstack.trigger.api.dto;

import lombok.Data;

/**
 * @author zpf
 * @description 活动抽奖请求对象
 * @create 2024-04-13 09:29
 */
@Data
public class ActivityDrawRequestDTO {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 活动ID
     */
    private Long activityId;

}
