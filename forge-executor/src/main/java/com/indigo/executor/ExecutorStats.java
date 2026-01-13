package com.indigo.executor;

/**
 * @author 史偕成
 * @date 2026/01/13 22:34
 **/
public interface ExecutorStats {


    /**
     * 获取当前活跃线程数
     * 活跃线程是指当前正在执行任务的线程数量
     *
     * @return 当前线程池中正在执行任务的线程数
     */
    int activeThreads();

    /**
     * 获取线程池大小
     * 线程池大小是指当前线程池中总的线程数量，包括活跃和空闲线程
     *
     * @return 当前线程池的总线程数
     */
    int poolSize();

    /**
     * 获取队列大小
     * 队列大小是指等待执行的任务数量
     *
     * @return 当前等待执行的任务数
     */
    int queuedTasks();

    /**
     * 获取已完成任务总数
     * 统计自线程池创建以来完成的任务总数
     *
     * @return 已完成的任务总数
     */
    long completedTasks();

    /**
     * 获取被拒绝的任务数
     * 当线程池达到最大容量且任务队列已满时，新提交的任务会被拒绝
     *
     * @return 被拒绝的任务总数
     */
    long rejectedTasks();
}
