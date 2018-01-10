package com.alibaba.otter.canal.parse.index;

import com.alibaba.otter.canal.common.AbstractCanalLifeCycle;
import com.alibaba.otter.canal.protocol.position.LogPosition;
import com.google.common.collect.MapMaker;

import java.util.Map;

/**
 * 基于内存的实现
 * 
 * @author jianghang 2012-7-7 上午10:17:23
 * @version 1.0.0
 */
// 将position记录在内存中
public class MemoryLogPositionManager extends AbstractCanalLifeCycle implements CanalLogPositionManager {

    protected Map<String, LogPosition> positions;

    public void start() {
        super.start();

        positions = new MapMaker().makeMap();
    }

    public void stop() {
        super.stop();

        positions.clear();
    }

    public LogPosition getLatestIndexBy(String destination) {
        return positions.get(destination);
    }

    public void persistLogPosition(String destination, LogPosition logPosition) {
        positions.put(destination, logPosition);
    }

}
