package com.insight.base.auth.manage;

import com.insight.base.auth.common.entity.InterfaceConfig;
import com.insight.util.pojo.Reply;

/**
 * @author 宣炳刚
 * @date 2019-09-02
 * @remark 配置管理服务接口
 */
public interface Service {

    /**
     * 获取接口配置列表
     *
     * @param key  查询关键词
     * @param page 分页页码
     * @param size 每页记录数
     * @return Reply
     */
    Reply getConfigs(String key, int page, int size);

    /**
     * 获取接口配置详情
     *
     * @param id 接口配置ID
     * @return Reply
     */
    Reply getConfig(String id);

    /**
     * 新增接口配置
     *
     * @param dto 接口配置
     * @return Reply
     */
    Reply newConfig(InterfaceConfig dto);

    /**
     * 编辑接口配置
     *
     * @param dto 接口配置DTO
     * @return Reply
     */
    Reply editConfig(InterfaceConfig dto);

    /**
     * 删除接口配置
     *
     * @param id 接口配置ID
     * @return Reply
     */
    Reply deleteConfig(String id);

    /**
     * 加载接口配置到缓存
     *
     * @return Reply
     */
    Reply loadConfigs();
}
