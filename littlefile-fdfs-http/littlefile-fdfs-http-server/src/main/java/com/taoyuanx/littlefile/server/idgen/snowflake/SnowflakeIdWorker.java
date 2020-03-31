package com.taoyuanx.littlefile.server.idgen.snowflake;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 
 * 2019年3月5日 下午6:03:57
 * @description 参见:https://gitee.com/zmds/ecp-uid
 *
 */
public class SnowflakeIdWorker {    
    // ============================== Constants===========================================
    private final static String ERROR_CLOCK_BACK = "时间回拨，拒绝为超出%d毫秒生成ID";
    
    private final static String ERROR_ATTR_LIMIT = "%s属性的范围为0-%d";
    
    private final static String MSG_UID_PARSE = "{\"UID\":\"%s\",\"timestamp\":\"%s\",\"workerId\":\"%d\",\"dataCenterId\":\"%d\",\"sequence\":\"%d\"}";
    
    private final static String DATE_PATTERN_DEFAULT = "yyyy-MM-dd HH:mm:ss";
    
    // ==============================Fields===========================================
    /** 开始时间截 (2017-12-25)，用于用当前时间戳减去这个时间戳，算出偏移量 */
    private final long twepoch = 1514131200000L;
    
    /** 机器id所占的位数(表示只允许workId的范围为：0-1023) */
    private final long workerIdBits = 5L;
    
    /** 数据标识id所占的位数 */
    private final long datacenterIdBits = 5L;
    
    /** 支持的最大机器id，结果是31 (这个移位算法可以很快的计算出几位二进制数所能表示的最大十进制数) */
    public final long maxWorkerId = -1L ^ (-1L << workerIdBits);
    
    /** 支持的最大数据标识id，结果是31 */
    private final long maxDatacenterId = -1L ^ (-1L << datacenterIdBits);
    
    /** 序列在id中占的位数 (表示只允许sequenceId的范围为：0-4095)*/
    private final long sequenceBits = 12L;
    
    /** 机器ID向左移12位 */
    private final long workerIdShift = sequenceBits;
    
    /** 数据标识id向左移17位(12+5) */
    private final long datacenterIdShift = sequenceBits + workerIdBits;
    
    /** 时间截向左移22位(5+5+12) */
    private final long timestampLeftShift = sequenceBits + workerIdBits + datacenterIdBits;
    
    /** 生成序列的掩码，(防止溢出:位与运算保证计算的结果范围始终是 0-4095，0b111111111111=0xfff=4095) */
    private final long sequenceMask = -1L ^ (-1L << sequenceBits);
    
    /** 工作机器ID(0~31) */
    private long workerId;
    
    /** 数据中心ID(0~31) */
    private long datacenterId;
    
    /** 毫秒内序列(0~4095) */
    private long sequence = 0L;
    
    /** 上次生成ID的时间截 */
    private long lastTimestamp = -1L;
    
    private boolean isClock = false;

    public void setClock(boolean clock) {
        isClock = clock;
    }
    
    // ==============================Constructors=====================================
    /**
     * 构造函数
     * 
     * @param workerId 工作ID (0~31)
     * @param datacenterId 数据中心ID (0~31)
     */
    public SnowflakeIdWorker(long workerId, long datacenterId) {
        if (workerId > maxWorkerId || workerId < 0) {
            throw new IllegalArgumentException(String.format(ERROR_ATTR_LIMIT, "workerId", maxWorkerId));
        }
        if (datacenterId > maxDatacenterId || datacenterId < 0) {
            throw new IllegalArgumentException(String.format(ERROR_ATTR_LIMIT, "datacenterId", maxDatacenterId));
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }
    
    // ==============================Methods==========================================
    /**
     * 获得下一个ID (该方法是线程安全的)
     * 
     * @return SnowflakeId
     */
    public synchronized long nextId() {
        long timestamp = timeGen();
        // 闰秒：如果当前时间小于上一次ID生成的时间戳，说明系统时钟回退过这个时候应当抛出异常
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= 5) {
                try {
                    // 时间偏差大小小于5ms，则等待两倍时间
                    wait(offset << 1);// wait
                    timestamp = timeGen();
                    if (timestamp < lastTimestamp) {
                        // 还是小于，抛异常并上报
                        throw new RuntimeException(String.format(ERROR_CLOCK_BACK, lastTimestamp - timestamp));
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            } else {
                throw new RuntimeException(String.format(ERROR_CLOCK_BACK, lastTimestamp - timestamp));
            }
        }
        
        // 解决跨毫秒生成ID序列号始终为偶数的缺陷:如果是同一时间生成的，则进行毫秒内序列
        if (lastTimestamp == timestamp) {
            // 通过位与运算保证计算的结果范围始终是 0-4095
            sequence = (sequence + 1) & sequenceMask;
            // 毫秒内序列溢出
            if (sequence == 0) {
                // 阻塞到下一个毫秒,获得新的时间戳
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            // 时间戳改变，毫秒内序列重置
            sequence = 0L;
        }
        
        // 上次生成ID的时间截
        lastTimestamp = timestamp;

        /*
         * 1.左移运算是为了将数值移动到对应的段(41、5、5，12那段因为本来就在最右，因此不用左移)
         * 2.然后对每个左移后的值(la、lb、lc、sequence)做位或运算，是为了把各个短的数据合并起来，合并成一个二进制数
         * 3.最后转换成10进制，就是最终生成的id(64位的ID)
         */
        return ((timestamp - twepoch) << timestampLeftShift) //
            | (datacenterId << datacenterIdShift) //
            | (workerId << workerIdShift) //
            | sequence;
    }
    
    /**
     * @方法名称 parseUID
     * @功能描述 <pre>反解析UID</pre>
     */
    public String parseUID(Long uid) {
        long totalBits = 64L;// 总位数
        long signBits = 1L;// 标识
        long timestampBits = 41L;// 时间戳
        // 解析Uid：标识 -- 时间戳 -- 数据中心 -- 机器码 --序列
        long sequence = (uid << (totalBits - sequenceBits)) >>> (totalBits - sequenceBits);
        long dataCenterId = (uid << (timestampBits + signBits)) >>> (totalBits - datacenterIdBits);
        long workerId = (uid << (timestampBits + signBits + datacenterIdBits)) >>> (totalBits - workerIdBits);
        long deltaSeconds = uid >>> (datacenterIdBits + workerIdBits + sequenceBits);
        // 时间处理(补上开始时间戳)
        Date thatTime = new Date(twepoch + deltaSeconds);
        String date = new SimpleDateFormat(DATE_PATTERN_DEFAULT).format(thatTime);
        // 格式化输出
        return String.format(MSG_UID_PARSE, uid, date, workerId, dataCenterId, sequence);
    }

    /**
     * @方法名称 parseUID
     * @功能描述 <pre>反解析UID(字符串截取，性能相对差；不推荐使用)</pre>
     */
    public String parseUID(String uid) {
        uid = Long.toBinaryString(Long.parseLong(uid));
        int len = uid.length();
        // 解析Uid：标识 -- 时间戳 -- 数据中心 -- 机器码 --序列
        int sequenceStart = len < workerIdShift ? 0 : (int)(len - workerIdShift);// sequence起始数
        int workerStart = len < datacenterIdShift ? 0 : (int)(len - datacenterIdShift);// worker起始数
        int timeStart = len < timestampLeftShift ? 0 : (int)(len - timestampLeftShift);// 时间起始数
        String sequence = uid.substring(sequenceStart, len);
        String workerId = sequenceStart == 0 ? "0" : uid.substring(workerStart, sequenceStart);
        String dataCenterId = workerStart == 0 ? "0" : uid.substring(timeStart, workerStart);
        // 时间处理(补上开始时间戳)
        String time = timeStart == 0 ? "0" : uid.substring(0, timeStart);
        Date timeDate = new Date(Long.parseLong(time, 2) + twepoch);
        String date = new SimpleDateFormat(DATE_PATTERN_DEFAULT).format(timeDate);
        // 格式化输出
        return String.format(MSG_UID_PARSE, uid, date, Integer.valueOf(workerId, 2), Integer.valueOf(dataCenterId, 2), Integer.valueOf(sequence, 2));
    }
    
    /**
     * 保证返回的毫秒数在参数之后(阻塞到下一个毫秒，直到获得新的时间戳)
     * 
     * @param lastTimestamp 上次生成ID的时间截
     * @return 当前时间戳
     */
    protected long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }
    
    /**
     * 获得系统当前毫秒数
     * 
     * @return 当前时间(毫秒)
     */
    protected long timeGen() {
        if (isClock) {
            // 解决高并发下获取时间戳的性能问题
            return SystemClock.now();
        } else {
            return System.currentTimeMillis();
        }
    }
    public static void main(String[] args) {
    	SnowflakeIdWorker s=new SnowflakeIdWorker(1L, 1L);
    	Long id=s.nextId();
        String uid=s.parseUID(id);
        System.out.println(uid);
	}
}