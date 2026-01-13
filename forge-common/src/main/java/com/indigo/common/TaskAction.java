package com.indigo.common;

/**
 * 最小任务可执行单元
 * @author 史偕成
 * @date 2026/01/13 22:27
 **/
public interface TaskAction {

    /**
     * Executes the task logic.
     * <p>
     * This method represents a unit of work and contains business logic only.
     * It must not carry execution semantics such as threading, scheduling, or
     * resource management. The actual execution semantics are handled by the
     * executor framework.
     * <p>
     * 执行任务逻辑。
     * <p>
     * 该方法代表一个工作单元，仅包含业务逻辑。不得携带执行语义，如线程管理、
     * 调度或资源管理。实际的执行语义由执行器框架处理。
     *
     * @implSpec Implementations should focus solely on the business logic
     *           of the task and should not include any execution-related concerns.
     *           实现应仅关注任务的业务逻辑，不应包含任何与执行相关的关注点。
     */
    void execute();
}
