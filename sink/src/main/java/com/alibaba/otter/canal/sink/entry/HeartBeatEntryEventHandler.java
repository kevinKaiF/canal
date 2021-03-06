package com.alibaba.otter.canal.sink.entry;

import com.alibaba.otter.canal.protocol.CanalEntry.EntryType;
import com.alibaba.otter.canal.sink.AbstractCanalEventDownStreamHandler;
import com.alibaba.otter.canal.store.model.Event;

import java.util.ArrayList;
import java.util.List;

/**
 * 处理一下一下heartbeat数据
 * 
 * @author jianghang 2013-10-8 下午6:03:53
 * @since 1.0.12
 */
public class HeartBeatEntryEventHandler extends AbstractCanalEventDownStreamHandler<List<Event>> {

    public List<Event> before(List<Event> events) {
        boolean existHeartBeat = false;
        for (Event event : events) {
            if (event.getEntry().getEntryType() == EntryType.HEARTBEAT) {
                existHeartBeat = true;
            }
        }

        if (!existHeartBeat) {
            return events;
        } else {
            // 目前heartbeat和其他事件是分离的，保险一点还是做一下检查处理
            // 过滤HeartBeat事件
            List<Event> result = new ArrayList<Event>();
            for (Event event : events) {
                if (event.getEntry().getEntryType() != EntryType.HEARTBEAT) {
                    result.add(event);
                }
            }

            return result;
        }
    }

}
