package io.transwarp.health.service.impl;

import io.transwarp.health.common.HealthConstants;
import io.transwarp.health.common.MetricTask;
import io.transwarp.health.configuration.properties.HbaseClientProperties;
import io.transwarp.health.service.MetricService;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetricServiceImpl implements MetricService, SmartInitializingSingleton, DisposableBean {

    private static final long BATCH_SIZE = 100;
    private static final long BATCH_MILLIS = 5000;
    private static final int MAX_QUEUE_SIZE = 100000;

    public static final Logger LOG = LoggerFactory.getLogger(MetricServiceImpl.class);

    private BlockingQueue<MetricTask> queue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    private AtomicLong ignorCount = new AtomicLong(0);
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean shutdown = false;

    @Resource
    private HConnection hConnection;

    @Resource
    private org.apache.hadoop.conf.Configuration hConfigration;


    @Resource
    HbaseClientProperties hbaseClientProperties;

    @Override
    public void afterSingletonsInstantiated() {
        executor.submit(() -> {
            this.consumeMetricTasks();
        });
    }

    @Override
    public void destroy() throws Exception {
        executor.shutdownNow();
        shutdown = true;
    }

    @Override
    public void addMetricTask(String id) {
        MetricTask task = new MetricTask();
        task.setId(id);
        try {
            this.queue.add(task);
        } catch (Exception e){
            // queue is full ignore it
            ignorCount.incrementAndGet();
            if (ignorCount.get() /1000 == 0){
                LOG.info("ignore pv put 1000");
                ignorCount.set(0);
            }
        }
    }

    @Override
    public void consumeMetricTasks() {
        while (!shutdown) {
            try {
                MetricTask task;
                long batchStartTime = System.currentTimeMillis();
                List<MetricTask> batch = new ArrayList<>();
                while (System.currentTimeMillis() - batchStartTime <= BATCH_MILLIS && (task = queue.poll(BATCH_MILLIS, TimeUnit.MILLISECONDS)) != null) {
                    batch.add(task);
                    if (batch.size() >= BATCH_SIZE) {
                        break;
                    }
                }

                // put to htable
                if (!batch.isEmpty()) {
                    DateFormat dateFormat = new SimpleDateFormat(HealthConstants.DATEFORMATE);
                    String tbName = hbaseClientProperties.getPvTableName() + "_" + dateFormat.format(new Date(System.currentTimeMillis()));
                    putData(tbName, batch);
                    LOG.debug("put data success");
                }
            } catch (Exception e) {
                LOG.error("put view access error", e);
            }
        }
    }

    @Override
    public void stopPvInsert() {
        executor.shutdownNow();
        shutdown = true;
    }


    public void putData(String tableName, List<MetricTask> taskList) throws Exception {

        TableName tName = TableName.valueOf(hbaseClientProperties.getDbName(), tableName);

        HTable hTable = new HTable(tName, hConnection);
        hTable.setAutoFlush(false);
        for (int i = 0; i < taskList.size(); i++) {
            Put put = new Put(Bytes.toBytes(taskList.get(i).getId()));
            put.add(Bytes.toBytes(hbaseClientProperties.getCfName()), Bytes.toBytes(hbaseClientProperties.getCqName()), Bytes.toBytes(""));
            try {
                hTable.put(put);
            } catch (Exception e) {
                throw e;
            }
        }
        hTable.flushCommits();
    }
}
