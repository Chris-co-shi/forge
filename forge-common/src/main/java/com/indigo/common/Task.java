package com.indigo.common;

import com.indigo.common.enums.TaskPriority;

/**
 * @author 史偕成
 * @date 2026/01/13 21:34
 **/
public interface Task {

    /**
     * 任务Id JVM 内唯一
     * @return
     */
    String id();

    /**
     * 任务名称
     * @return
     */
    String name();

    /**
     * 任务优先级
     * @return
     */
    TaskPriority priority();

    /**
     * 任务执行动作
     * @return
     */
    TaskAction action();
}
