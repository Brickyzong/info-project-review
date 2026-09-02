package com.xmps.config;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 异步任务配置属性
 */
@Getter
@Setter
public class AsyncProperties {
    private int corePoolSize = 4;
    private int maxPoolSize = 8;
    private int queueCapacity = 100;
    private String threadNamePrefix = "xmps-async-";
}
