package com.alibaba.otter.canal.instance.core;

import com.alibaba.otter.canal.common.CanalLifeCycle;
import com.alibaba.otter.canal.common.alarm.CanalAlarmHandler;
import com.alibaba.otter.canal.meta.CanalMetaManager;
import com.alibaba.otter.canal.parse.CanalEventParser;
import com.alibaba.otter.canal.protocol.ClientIdentity;
import com.alibaba.otter.canal.sink.CanalEventSink;
import com.alibaba.otter.canal.store.CanalEventStore;

/**
 * 代表单个canal实例，比如一个destination会独立一个实例
 * 
 * @author jianghang 2012-7-12 下午12:04:58
 * @version 1.0.0
 */
public interface CanalInstance extends CanalLifeCycle {
    // binlog的服务器
    String getDestination();
    // binlog解析处理，再加工
    CanalEventParser getEventParser();

    // binlog数据的过滤处理
    CanalEventSink getEventSink();

    // binlog数据加工后的存储地址
    CanalEventStore getEventStore();

    // 客户端订阅的管理器
    CanalMetaManager getMetaManager();

    // 告警
    CanalAlarmHandler getAlarmHandler();

    /**
     * 客户端发生订阅/取消订阅行为
     */
    // 客户端是否有订阅
    boolean subscribeChange(ClientIdentity identity);
}
