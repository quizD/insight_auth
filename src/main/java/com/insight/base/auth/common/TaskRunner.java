package com.insight.base.auth.common;

import com.insight.base.auth.common.mapper.ConfigMapper;
import com.insight.utils.Json;
import com.insight.utils.Redis;
import com.insight.utils.common.ApplicationContextHolder;
import com.insight.utils.pojo.InterfaceDto;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.List;

/**
 * @author 宣炳刚
 * @date 2019-09-11
 * @remark 初始化数据加载器
 */
public class TaskRunner implements ApplicationRunner {
    private final ConfigMapper mapper = ApplicationContextHolder.getContext().getBean(ConfigMapper.class);

    @Override
    public void run(ApplicationArguments args) {
        List<InterfaceDto> configs = mapper.loadConfigs();
        if (configs == null || configs.isEmpty()) {
            return;
        }

        String json = Json.toJson(configs);
        Redis.set("Config:Interface", json);
        Redis.set("Config:DefaultHead", "head_default.png");
        Redis.set("Config:FileHost", "https://images.insight.com/");
    }
}
