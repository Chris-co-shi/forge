package com.indigo.executor;

import com.indigo.common.Task;

/**
 * @author 史偕成
 * @date 2026/01/13 21:40
 **/
@Deprecated
public class SimpleExecutor implements Executor{
    @Override
    public void submit(Task task) {
//        new Thread(task::execute).start();
    }
}
