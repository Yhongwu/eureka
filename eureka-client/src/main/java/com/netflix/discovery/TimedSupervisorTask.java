package com.netflix.discovery;

import java.util.TimerTask;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.LongGauge;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A supervisor task that schedules subtasks while enforce a timeout.
 * Wrapped subtasks must be thread safe.
 *
 * 继承与jdk的TimerTask
 *
 * @author David Qiang Liu
 */
public class TimedSupervisorTask extends TimerTask {
    private static final Logger logger = LoggerFactory.getLogger(TimedSupervisorTask.class);

    private final Counter timeoutCounter;
    private final Counter rejectedCounter;
    private final Counter throwableCounter;
    private final LongGauge threadPoolLevelGauge;

    private final ScheduledExecutorService scheduler;
    private final ThreadPoolExecutor executor;
    private final long timeoutMillis;
    private final Runnable task;

    private final AtomicLong delay;
    private final long maxDelay;

    public TimedSupervisorTask(String name, ScheduledExecutorService scheduler, ThreadPoolExecutor executor,
                               int timeout, TimeUnit timeUnit, int expBackOffBound, Runnable task) {
        this.scheduler = scheduler;
        this.executor = executor;
        this.timeoutMillis = timeUnit.toMillis(timeout);
        this.task = task;
        this.delay = new AtomicLong(timeoutMillis);
        this.maxDelay = timeoutMillis * expBackOffBound;

        // Initialize the counters and register.
        timeoutCounter = Monitors.newCounter("timeouts");
        rejectedCounter = Monitors.newCounter("rejectedExecutions");
        throwableCounter = Monitors.newCounter("throwables");
        threadPoolLevelGauge = new LongGauge(MonitorConfig.builder("threadPoolUsed").build());
        Monitors.registerObject(name, this);
    }

    @Override
    public void run() {
        Future<?> future = null;
        try {
            // 提交执行任务 task才是真正的心跳或者刷新缓存的任务逻辑
            future = executor.submit(task);
            threadPoolLevelGauge.set((long) executor.getActiveCount());
            // future获取结果 阻塞
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);  // block until done or timeout
            // 设置下次执行间隔 如果执行成功 把时间重新设置为默认的超时时间
            delay.set(timeoutMillis);
            threadPoolLevelGauge.set((long) executor.getActiveCount());
        } catch (TimeoutException e) {
            logger.warn("task supervisor timed out", e);
            // 如果超时 记录超时次数
            timeoutCounter.increment();
            // 超时时间翻倍 因为如果一开始超时 后面每次还是同样时间30s 很可能也会超时 这样每次都会超时
            long currentDelay = delay.get();
            // 与阈值相比取最小
            long newDelay = Math.min(maxDelay, currentDelay * 2);
            // cas 设置超时时间
            delay.compareAndSet(currentDelay, newDelay);

        } catch (RejectedExecutionException e) {
            // 任务被拒绝 统计拒绝次数
            if (executor.isShutdown() || scheduler.isShutdown()) {
                logger.warn("task supervisor shutting down, reject the task", e);
            } else {
                logger.warn("task supervisor rejected the task", e);
            }

            rejectedCounter.increment();
        } catch (Throwable e) {
            if (executor.isShutdown() || scheduler.isShutdown()) {
                logger.warn("task supervisor shutting down, can't accept the task");
            } else {
                logger.warn("task supervisor threw an exception", e);
            }
            // 统计异常次数
            throwableCounter.increment();
        } finally {
            // 取消未结束的任务
            if (future != null) {
                future.cancel(true);
            }

            if (!scheduler.isShutdown()) {
                // 如果定时任务服务未关闭，定义下一次任务
                // 不断循环
                scheduler.schedule(this, delay.get(), TimeUnit.MILLISECONDS);
            }
        }
    }
}