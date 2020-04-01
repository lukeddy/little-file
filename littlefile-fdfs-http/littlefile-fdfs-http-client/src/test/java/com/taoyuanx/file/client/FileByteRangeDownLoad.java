package com.taoyuanx.file.client;

import com.taoyuanx.littlefile.client.ex.FdfsException;
import com.taoyuanx.littlefile.client.utils.OkHttpUtil;
import com.taoyuanx.littlefile.fdfshttp.core.client.FileClient;
import com.taoyuanx.littlefile.fdfshttp.core.dto.FileInfo;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * @author dushitaoyuan
 * @desc 断点下载
 * @date 2019/7/8
 */
public class FileByteRangeDownLoad {
    private String fileId;
    private FileClient client;
    private Long fileSize;

    private Integer downLoadThreadNum = 3;
    private OutputStream outputStream;

    private static ThreadPoolExecutor poolExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(6);

    public FileByteRangeDownLoad(FileClient client, String fileId, OutputStream outputStream) {
        this.client = client;
        this.fileId = fileId;
        this.outputStream = outputStream;
    }

    public FileByteRangeDownLoad(FileClient client, Long fileSize, OutputStream outputStream) {
        this.client = client;
        this.fileSize = fileSize;
        this.outputStream = outputStream;
    }

    public void downLoad() throws Exception {

        long totalSize = getFileSize();
        if (totalSize > 4 * 1024 * 1024) {
            LongAdder count = new LongAdder();
            List<Future<byte[]>> downLoadList = OkHttpUtil.range(totalSize, downLoadThreadNum).stream().map(byteRange -> {
                return poolExecutor.submit(new Callable<byte[]>() {
                    @Override
                    public byte[] call() throws Exception {
                        Long len = byteRange.getEnd() - byteRange.getStrat() + 1;
                        count.add(len);
                        System.out.println("start:" + byteRange.getStrat() + "\t end:" + byteRange.getEnd() + "\t" + len);
                        return client.downLoadRange(fileId, byteRange.getStrat(), byteRange.getEnd());

                    }
                });
            }).collect(Collectors.toList());
            for (Future<byte[]> bytes : downLoadList) {
                outputStream.write(bytes.get());
            }
            System.out.println("下载完成,文件大小:" + totalSize+"下载总大小:"+count.longValue());
        } else {
            Long start = System.currentTimeMillis();
            client.downLoad(fileId, outputStream);
            Long end = System.currentTimeMillis();
            System.out.println("下载耗时:" + (end - start));
        }
    }

    private Long getFileSize() {
        if (Objects.nonNull(fileSize)) {
            return fileSize;
        }
        FileInfo fileInfo = client.getFileInfo(fileId);
        if (Objects.isNull(fileInfo)) {
            throw new FdfsException("文件不存在");
        }
        return fileInfo.getFile_size();
    }


}
