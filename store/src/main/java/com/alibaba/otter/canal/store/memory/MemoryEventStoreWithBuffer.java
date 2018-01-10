package com.alibaba.otter.canal.store.memory;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.CanalEntry.EventType;
import com.alibaba.otter.canal.protocol.position.LogPosition;
import com.alibaba.otter.canal.protocol.position.Position;
import com.alibaba.otter.canal.protocol.position.PositionRange;
import com.alibaba.otter.canal.store.AbstractCanalStoreScavenge;
import com.alibaba.otter.canal.store.CanalEventStore;
import com.alibaba.otter.canal.store.CanalStoreException;
import com.alibaba.otter.canal.store.CanalStoreScavenge;
import com.alibaba.otter.canal.store.helper.CanalEventUtils;
import com.alibaba.otter.canal.store.model.BatchMode;
import com.alibaba.otter.canal.store.model.Event;
import com.alibaba.otter.canal.store.model.Events;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于内存buffer构建内存memory store
 * 
 * <pre>
 * 变更记录：
 * 1. 新增BatchMode类型，支持按内存大小获取批次数据，内存大小更加可控.
 *   a. put操作，会首先根据bufferSize进行控制，然后再进行bufferSize * bufferMemUnit进行控制. 因存储的内容是以Event，如果纯依赖于memsize进行控制，会导致RingBuffer出现动态伸缩
 * </pre>
 * 
 * @author jianghang 2012-6-20 上午09:46:31
 * @version 1.0.0
 */
public class MemoryEventStoreWithBuffer extends AbstractCanalStoreScavenge implements CanalEventStore<Event>, CanalStoreScavenge {

    private static final long INIT_SQEUENCE = -1;
    private int               bufferSize    = 16 * 1024;
    private int               bufferMemUnit = 1024;                         // memsize的单位，默认为1kb大小
    private int               indexMask;
    private Event[]           entries;

    // 记录下put/get/ack操作的三个下标
    private AtomicLong        putSequence   = new AtomicLong(INIT_SQEUENCE); // 代表当前put操作最后一次写操作发生的位置
    private AtomicLong        getSequence   = new AtomicLong(INIT_SQEUENCE); // 代表当前get操作读取的最后一条的位置
    private AtomicLong        ackSequence   = new AtomicLong(INIT_SQEUENCE); // 代表当前ack操作的最后一条的位置

    // 记录下put/get/ack操作的三个memsize大小
    private AtomicLong        putMemSize    = new AtomicLong(0);
    private AtomicLong        getMemSize    = new AtomicLong(0);
    private AtomicLong        ackMemSize    = new AtomicLong(0);

    // 阻塞put/get操作控制信号
    private ReentrantLock     lock          = new ReentrantLock();
    private Condition         notFull       = lock.newCondition();
    private Condition         notEmpty      = lock.newCondition();

    private BatchMode         batchMode     = BatchMode.ITEMSIZE;           // 默认为内存大小模式
    private boolean           ddlIsolation  = false;

    public MemoryEventStoreWithBuffer(){

    }

    public MemoryEventStoreWithBuffer(BatchMode batchMode){
        this.batchMode = batchMode;
    }

    public void start() throws CanalStoreException {
        super.start();
        // bufferSize buffer的内存大小
        if (Integer.bitCount(bufferSize) != 1) {
            throw new IllegalArgumentException("bufferSize must be a power of 2");
        }

        // 生成掩码
        indexMask = bufferSize - 1;
        // 多个缓存数据
        entries = new Event[bufferSize];
    }

    public void stop() throws CanalStoreException {
        super.stop();

        cleanAll();
    }

    /**
     * 一直尝试
     *
     * @param data
     * @throws InterruptedException
     * @throws CanalStoreException
     */
    public void put(List<Event> data) throws InterruptedException, CanalStoreException {
        if (data == null || data.isEmpty()) {
            return;
        }

        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            try {
                while (!checkFreeSlotAt(putSequence.get() + data.size())) { // 检查是否有空位
                    notFull.await(); // wait until not full
                }
            } catch (InterruptedException ie) {
                notFull.signal(); // propagate to non-interrupted thread
                throw ie;
            }
            doPut(data);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在timeout时间内一直尝试
     *
     * @param data
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     * @throws CanalStoreException
     */
    public boolean put(List<Event> data, long timeout, TimeUnit unit) throws InterruptedException, CanalStoreException {
        if (data == null || data.isEmpty()) {
            return true;
        }

        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            for (;;) {
                if (checkFreeSlotAt(putSequence.get() + data.size())) {
                    doPut(data);
                    return true;
                }
                if (nanos <= 0) {
                    return false;
                }

                try {
                    // 剩余时间
                    nanos = notFull.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    notFull.signal(); // propagate to non-interrupted thread
                    throw ie;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 只做一次尝试
     *
     * @param data
     * @return
     * @throws CanalStoreException
     */
    public boolean tryPut(List<Event> data) throws CanalStoreException {
        if (data == null || data.isEmpty()) {
            return true;
        }

        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (!checkFreeSlotAt(putSequence.get() + data.size())) {
                return false;
            } else {
                doPut(data);
                return true;
            }
        } finally {
            lock.unlock();
        }
    }

    public void put(Event data) throws InterruptedException, CanalStoreException {
        put(Arrays.asList(data));
    }

    public boolean put(Event data, long timeout, TimeUnit unit) throws InterruptedException, CanalStoreException {
        return put(Arrays.asList(data), timeout, unit);
    }

    public boolean tryPut(Event data) throws CanalStoreException {
        return tryPut(Arrays.asList(data));
    }

    /**
     * 执行具体的put操作
     */
    private void doPut(List<Event> data) {
        // putSequence 只记录event的条数

        // 记录当前put操作的位置
        long current = putSequence.get();
        long end = current + data.size();

        // 先写数据，再更新对应的cursor,并发度高的情况，putSequence会被get请求可见，拿出了ringbuffer中的老的Entry值
        for (long next = current + 1; next <= end; next++) {
            // getIndex进行取余操作，获取entry的位置
            // next-current-1从0开始获取data数据
            entries[getIndex(next)] = data.get((int) (next - current - 1));
        }

        // 记录put的最新位置
        putSequence.set(end);

        // 记录一下gets memsize信息，方便快速检索
        if (batchMode.isMemSize()) {
            long size = 0;
            for (Event event : data) {
                size += calculateSize(event);
            }
            // putMemSize 记录内存数据字节的大小
            putMemSize.getAndAdd(size);
        }

        // tell other threads that store is not empty
        notEmpty.signal();
    }

    /**
     * 一直尝试
     *
     * @param start
     * @param batchSize
     * @return
     * @throws InterruptedException
     * @throws CanalStoreException
     */
    public Events<Event> get(Position start, int batchSize) throws InterruptedException, CanalStoreException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            try {
                while (!checkUnGetSlotAt((LogPosition) start, batchSize))
                    notEmpty.await();
            } catch (InterruptedException ie) {
                notEmpty.signal(); // propagate to non-interrupted thread
                throw ie;
            }

            return doGet(start, batchSize);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按时间阻塞读取，时间到了有多少读取多少
     *
     * @param start
     * @param batchSize
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     * @throws CanalStoreException
     */
    public Events<Event> get(Position start, int batchSize, long timeout, TimeUnit unit) throws InterruptedException,
                                                                                        CanalStoreException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            for (;;) {
                if (checkUnGetSlotAt((LogPosition) start, batchSize)) {
                    return doGet(start, batchSize);
                }

                if (nanos <= 0) {
                    // 如果时间到了，有多少取多少
                    return doGet(start, batchSize);
                }

                try {
                    nanos = notEmpty.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    notEmpty.signal(); // propagate to non-interrupted thread
                    throw ie;
                }

            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 直接读取
     *
     * @param start
     * @param batchSize
     * @return
     * @throws CanalStoreException
     */
    public Events<Event> tryGet(Position start, int batchSize) throws CanalStoreException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return doGet(start, batchSize);
        } finally {
            lock.unlock();
        }
    }

    private Events<Event> doGet(Position start, int batchSize) throws CanalStoreException {
        LogPosition startPosition = (LogPosition) start;
        // getSequence 当前读取条数的位置
        long current = getSequence.get();
        // putSequence 当前写入条数的位置
        long maxAbleSequence = putSequence.get();
        long next = current;
        long end = current;
        // 如果startPosition为null，说明是第一次，默认+1处理
        if (startPosition == null || !startPosition.getPostion().isIncluded()) { // 第一次订阅之后，需要包含一下start位置，防止丢失第一条记录
            next = next + 1;
        }

        // 如果当前读取条数大于等于写入条数，则返回空数据
        if (current >= maxAbleSequence) {
            return new Events<Event>();
        }

        Events<Event> result = new Events<Event>();
        List<Event> entrys = result.getEvents();
        // 将数据添加到entrys
        long memsize = 0;
        // 如果是按条数
        if (batchMode.isItemSize()) {
            // end就是本次批量读取的lastIndex
            // -1是因为是下标
            end = (next + batchSize - 1) < maxAbleSequence ? (next + batchSize - 1) : maxAbleSequence;
            // 提取数据并返回
            for (; next <= end; next++) {
                Event event = entries[getIndex(next)];
                // 如果只需要ddl数据，并且只读一次
                // EventType.ALTER || EventType.CREATE || EventType.ERASE|| EventType.RENAME || EventType.TRUNCATE || EventType.CINDEX|| EventType.DINDEX
                if (ddlIsolation && isDdl(event.getEntry().getHeader().getEventType())) {
                    // 如果是ddl隔离，直接返回
                    if (entrys.size() == 0) {
                        entrys.add(event);// 如果没有DML事件，加入当前的DDL事件
                        end = next; // 更新end为当前
                    } else {
                        // 如果之前已经有DML事件，直接返回了，因为不包含当前next这记录，需要回退一个位置
                        end = next - 1; // next-1一定大于current，不需要判断
                    }
                    break;
                } else {  // 否则全部添加
                    entrys.add(event);
                }
            }
        } else {
            // 批量读取的字节数
            long maxMemSize = batchSize * bufferMemUnit;
            // memsize记录了读取的数据量，每次读取完需要累计
            for (; memsize <= maxMemSize && next <= maxAbleSequence; next++) {
                // 永远保证可以取出第一条的记录，避免死锁
                Event event = entries[getIndex(next)];
                if (ddlIsolation && isDdl(event.getEntry().getHeader().getEventType())) {
                    // 如果是ddl隔离，直接返回
                    if (entrys.size() == 0) {
                        entrys.add(event);// 如果没有DML事件，加入当前的DDL事件
                        end = next; // 更新end为当前
                    } else {
                        // 如果之前已经有DML事件，直接返回了，因为不包含当前next这记录，需要回退一个位置
                        end = next - 1; // next-1一定大于current，不需要判断
                    }
                    break;
                } else {
                    entrys.add(event);
                    memsize += calculateSize(event);
                    end = next;// 记录end位点
                }
            }

        }

        PositionRange<LogPosition> range = new PositionRange<LogPosition>();
        result.setPositionRange(range);
        // 将entrys中的第一条数据记录为range的start
        // 将entrys中的最后一条数据记录为range的end
        range.setStart(CanalEventUtils.createPosition(entrys.get(0)));
        range.setEnd(CanalEventUtils.createPosition(entrys.get(result.getEvents().size() - 1)));
        // 记录一下是否存在可以被ack的点

        // 倒序遍历，判断是否有transactionBegin,transactionEnd,ddl操作的事件
        // 作为ack的记录点
        for (int i = entrys.size() - 1; i >= 0; i--) {
            Event event = entrys.get(i);
            if (CanalEntry.EntryType.TRANSACTIONBEGIN == event.getEntry().getEntryType()
                || CanalEntry.EntryType.TRANSACTIONEND == event.getEntry().getEntryType()
                || isDdl(event.getEntry().getHeader().getEventType())) {
                // 将事务头/尾设置可被为ack的点
                range.setAck(CanalEventUtils.createPosition(event));
                break;
            }
        }

        // 重置读取的位置，end是最后读取的条数位置
        if (getSequence.compareAndSet(current, end)) {
            // 更新读取内存字节的数目
            getMemSize.addAndGet(memsize);
            notFull.signal();
            return result;
        } else {
            // 如果重置失败就返回空数据
            return new Events<Event>();
        }
    }

    public LogPosition getFirstPosition() throws CanalStoreException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            long firstSeqeuence = ackSequence.get();
            // 如果没有ack，则读取entries中的第一条数据
            if (firstSeqeuence == INIT_SQEUENCE && firstSeqeuence < putSequence.get()) {
                // 没有ack过数据
                Event event = entries[getIndex(firstSeqeuence + 1)]; // 最后一次ack为-1，需要移动到下一条,included
                                                                     // = false
                return CanalEventUtils.createPosition(event, false);
                // 如果有ack数据，但是没到达最新的putSequence位置，读取ack下一条数据
            } else if (firstSeqeuence > INIT_SQEUENCE && firstSeqeuence < putSequence.get()) {
                // ack未追上put操作
                Event event = entries[getIndex(firstSeqeuence + 1)]; // 最后一次ack的位置数据
                                                                     // + 1
                // 这里的true表示包含的意思，说明还有未读取的数据？
                return CanalEventUtils.createPosition(event, true);
                // 如果ack的位置和put位置一样，则读取当前ack位置的数据
            } else if (firstSeqeuence > INIT_SQEUENCE && firstSeqeuence == putSequence.get()) {
                // 已经追上，store中没有数据
                Event event = entries[getIndex(firstSeqeuence)]; // 最后一次ack的位置数据，和last为同一条，included
                                                                 // = false
                return CanalEventUtils.createPosition(event, false);
            } else {
                // 没有任何数据
                return null;
            }
        } finally {
            lock.unlock();
        }
    }

    public LogPosition getLatestPosition() throws CanalStoreException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            long latestSequence = putSequence.get();
            // 如果put中写入了数据且不同于ack数据
            // 读取当前put位置的数据
            if (latestSequence > INIT_SQEUENCE && latestSequence != ackSequence.get()) {
                Event event = entries[(int) putSequence.get() & indexMask]; // 最后一次写入的数据，最后一条未消费的数据
                // true表示还有未读取的数据
                return CanalEventUtils.createPosition(event, true);
            } else if (latestSequence > INIT_SQEUENCE && latestSequence == ackSequence.get()) {
                // ack已经追上了put操作
                Event event = entries[(int) putSequence.get() & indexMask]; // 最后一次写入的数据，included
                                                                            // =
                                                                            // false
                return CanalEventUtils.createPosition(event, false);
            } else {
                // 没有任何数据
                return null;
            }
        } finally {
            lock.unlock();
        }
    }

    public void ack(Position position) throws CanalStoreException {
        cleanUntil(position);
    }

    public void cleanUntil(Position position) throws CanalStoreException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // ackSequence 记录ack的条数位置
            long sequence = ackSequence.get();
            // getSequence 记录读取的条数位置
            long maxSequence = getSequence.get();

            boolean hasMatch = false;
            long memsize = 0;
            for (long next = sequence + 1; next <= maxSequence; next++) {
                Event event = entries[getIndex(next)];
                memsize += calculateSize(event);
                boolean match = CanalEventUtils.checkPosition(event, (LogPosition) position);
                if (match) {// 找到对应的position，更新ack seq
                    hasMatch = true;

                    if (batchMode.isMemSize()) {
                        // 如果是内存模式，ackMemSize 记录ack的字节数量
                        ackMemSize.addAndGet(memsize);
                        // 尝试清空buffer中的内存，将ack之前的内存全部释放掉
                        // 将ack之前的内存数据全部清空
                        for (long index = sequence + 1; index < next; index++) {
                            entries[getIndex(index)] = null;// 设置为null
                        }
                    }

                    // 重置ack条数
                    if (ackSequence.compareAndSet(sequence, next)) {// 避免并发ack
                        notFull.signal();
                        return;
                    }
                }
            }

            // 如果没找到则抛出异常
            if (!hasMatch) {// 找不到对应需要ack的position
                throw new CanalStoreException("no match ack position" + position.toString());
            }
        } finally {
            lock.unlock();
        }
    }

    public void rollback() throws CanalStoreException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 将读取条数位置 重置ackSequence条数的位置
            getSequence.set(ackSequence.get());
            // 将读取内存字节位置 重置ackMemSize字节位置
            getMemSize.set(ackMemSize.get());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 重置
     *
     * @throws CanalStoreException
     */
    public void cleanAll() throws CanalStoreException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            putSequence.set(INIT_SQEUENCE);
            getSequence.set(INIT_SQEUENCE);
            ackSequence.set(INIT_SQEUENCE);

            putMemSize.set(0);
            getMemSize.set(0);
            ackMemSize.set(0);
            entries = null;
            // for (int i = 0; i < entries.length; i++) {
            // entries[i] = null;
            // }
        } finally {
            lock.unlock();
        }
    }

    // =================== helper method =================

    /**
     * 获取ackSequence和getSequence的最小位置
     * @return
     */
    private long getMinimumGetOrAck() {
        long get = getSequence.get();
        long ack = ackSequence.get();
        return ack <= get ? ack : get;
    }

    /**
     * 查询是否有空位
     */
    private boolean checkFreeSlotAt(final long sequence) {
        final long wrapPoint = sequence - bufferSize;
        final long minPoint = getMinimumGetOrAck();
        // 如果wrapPoint > minPoint 说明bufferSize中的数据还没有完全消费
        // 因为wrapPoint是减去bufferSize的,bufferSize代表了一个数据窗口
        if (wrapPoint > minPoint) { // 刚好追上一轮
            return false;
        } else {
            // 在bufferSize模式上，再增加memSize控制
            if (batchMode.isMemSize()) {
                // putMemSize 记录了写入内存数据量的位置
                // ackMemSize 记录了读取内存数据量的位置
                final long memsize = putMemSize.get() - ackMemSize.get();
                if (memsize < bufferSize * bufferMemUnit) {
                    return true;
                } else {
                    return false;
                }
            } else {
                return true;
            }
        }
    }

    /**
     * 检查是否存在需要get的数据,并且数量>=batchSize
     */
    private boolean checkUnGetSlotAt(LogPosition startPosition, int batchSize) {
        // 如果是按条数计算
        if (batchMode.isItemSize()) {
            // getSequence 记录读取数据的条数
            long current = getSequence.get();
            // putSequence 记录写入数据的条数
            long maxAbleSequence = putSequence.get();
            long next = current;
            if (startPosition == null || !startPosition.getPostion().isIncluded()) { // 第一次订阅之后，需要包含一下start位置，防止丢失第一条记录
                next = next + 1;// 少一条数据
            }

            // 读取的位置肯定得小于写入的位置
            // 且保证未读取的条数大于一次批量
            if (current < maxAbleSequence && next + batchSize - 1 <= maxAbleSequence) {
                return true;
            } else {
                return false;
            }
        } else {
            // 处理内存大小判断
            // getMemSize 记录读取内存数据字节的位置
            long currentSize = getMemSize.get();
            // putMemSize 记录写入内存数据字节的位置
            long maxAbleSize = putMemSize.get();

            // 确保未读取的数据量在批量大小内
            if (maxAbleSize - currentSize >= batchSize * bufferMemUnit) {
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * 计算每次mysql事件的字节数大小
     *
     * @param event
     * @return
     */
    private long calculateSize(Event event) {
        // 直接返回binlog中的事件大小
        return event.getEntry().getHeader().getEventLength();
    }

    // 取余操作
    private int getIndex(long sequcnce) {
        return (int) sequcnce & indexMask;
    }

    private boolean isDdl(EventType type) {
        return type == EventType.ALTER || type == EventType.CREATE || type == EventType.ERASE
               || type == EventType.RENAME || type == EventType.TRUNCATE || type == EventType.CINDEX
               || type == EventType.DINDEX;
    }

    // ================ setter / getter ==================

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public void setBufferMemUnit(int bufferMemUnit) {
        this.bufferMemUnit = bufferMemUnit;
    }

    public void setBatchMode(BatchMode batchMode) {
        this.batchMode = batchMode;
    }

    public void setDdlIsolation(boolean ddlIsolation) {
        this.ddlIsolation = ddlIsolation;
    }

}
