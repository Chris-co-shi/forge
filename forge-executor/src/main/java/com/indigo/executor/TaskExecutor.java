package com.indigo.executor;

import com.indigo.common.Task;

/**
 * @author 史偕成
 * @date 2026/01/13 22:33
 **/
public interface TaskExecutor {

    void submit(Task task);

    void shutdown();

    void shutdownNow();

    ExecutorStats stats();
}
