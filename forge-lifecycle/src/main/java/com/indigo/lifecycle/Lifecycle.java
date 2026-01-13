package com.indigo.lifecycle;

/**
 * @description: 抽象生命周期
 * @author 史偕成
 * @date 2026/01/13 21:39
 **/
public interface Lifecycle {

    /**
     * 启动
     */
    void start();

    /**
     * 停止
     */
    void stop();

    /**
     * 是否运行中
     *
     * @return true
     */
    boolean isRunning();
}
