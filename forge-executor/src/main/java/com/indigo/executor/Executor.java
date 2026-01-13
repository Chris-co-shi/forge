package com.indigo.executor;

import com.indigo.common.Task;

/**
 * @author 史偕成
 * @date 2026/01/13 21:37
 **/
@Deprecated
public interface Executor {

    /**
     * Submit a task for execution.
     * @param task The task to be executed.
     */
    void submit(Task task);
}
