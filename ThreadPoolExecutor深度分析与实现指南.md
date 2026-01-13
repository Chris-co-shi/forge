# ThreadPoolExecutor 深度分析与实现指南

## 1. 引言：线程池技术概述与学习目标

### 1.1 线程池技术背景与意义

在现代并发编程中，线程池已成为处理大量异步任务的核心技术。ThreadPoolExecutor 作为 Java 并发包中功能最强大、最核心的线程池实现，其设计基于生产者 - 消费者模式，通过内部维护的工作线程池来执行提交的任务，从而避免频繁创建和销毁线程带来的性能开销[(1)](https://blog.csdn.net/kkjt0130/article/details/153129046)。

线程池技术解决了两个关键问题：首先，减少了任务调用的开销，通常在执行大量异步任务时性能会显著提升；其次，为执行任务集合时消耗的资源（包括线程）提供了边界设定和管理方法[(16)](https://docs.oracle.com/javase/jp/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)。每个 ThreadPoolExecutor 还维护基本的统计信息，如已完成任务的数量等[(16)](https://docs.oracle.com/javase/jp/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)。

在高并发场景下，合理使用线程池不仅能够提高系统吞吐量，还能有效控制资源消耗，避免因线程创建过多导致的系统崩溃。例如，在电商秒杀、实时数据处理、微服务架构等场景中，线程池都是不可或缺的基础设施。

### 1.2 学习目标与实践路径

本指南的核心目标是帮助读者通过深入分析 ThreadPoolExecutor 的设计原理和实现细节，掌握其核心机制，并能够独立实现一个简化版的线程池。通过理论学习与实践相结合的方式，加深对线程池工作原理的理解。

具体学习目标包括：



* 理解 ThreadPoolExecutor 的核心设计原理，包括工作队列、线程复用、拒绝策略等

* 掌握 ThreadPoolExecutor 的具体实现细节，如状态管理、任务调度、线程创建逻辑

* 了解线程池的高级功能，如监控、扩展钩子、预启动线程等

* 能够阅读和理解官方文档及源码

* 具备实现简化版线程池的能力

实践路径建议采用渐进式学习方法：先从理论理解入手，然后通过阅读官方文档和源码加深认识，最后通过自主实现来巩固所学知识。在实现过程中，建议从最简单的版本开始，逐步添加功能，最终实现一个功能完整的线程池。

## 2. ThreadPoolExecutor 核心设计原理

### 2.1 工作队列机制分析

ThreadPoolExecutor 的工作队列是其核心组件之一，负责存储等待执行的任务。工作队列的选择直接影响线程池的行为特性和性能表现。

#### 2.1.1 队列类型与特性

ThreadPoolExecutor 支持多种类型的阻塞队列，每种队列都有其特定的适用场景：



1. **SynchronousQueue（同步队列）**

* 特性：无容量，不存储任务，每个插入操作必须等待对应的移除操作

* 适用场景：适合短时间内有大量任务提交的场景（如 CachedThreadPool），需要快速响应的实时系统

* 风险：若线程创建速度跟不上任务提交速度，可能导致线程数激增，引发 OOM

1. **LinkedBlockingQueue（链表阻塞队列）**

* 特性：默认无界（容量为 Integer.MAX\_VALUE），也可指定容量，FIFO 顺序

* 适用场景：固定线程数的线程池（如 FixedThreadPool），任务执行时间较均匀的场景

* 风险：无界队列可能导致任务无限堆积，引发 OOM

1. **ArrayBlockingQueue（数组阻塞队列）**

* 特性：有界队列，必须指定初始容量，FIFO 顺序，通过 ReentrantLock 保证线程安全

* 适用场景：需要严格控制队列长度的场景，防止资源耗尽，结合拒绝策略使用

1. **PriorityBlockingQueue（优先级阻塞队列）**

* 特性：无界队列，基于优先级（实现 Comparable 接口）排序，而非 FIFO

* 适用场景：任务有优先级差异的场景（如订单处理、紧急任务）

1. **DelayedWorkQueue（延迟队列）**

* 特性：基于优先级，任务按延迟时间排序，延迟时间最短的先执行

* 适用场景：定时任务或延迟任务（如 ScheduledThreadPoolExecutor）

#### 2.1.2 队列选择策略

队列的选择需要根据具体的应用场景和性能需求来决定：

**CPU 密集型任务**：建议使用有界队列防止过度排队，避免线程数过多导致的上下文切换开销。可选择 ArrayBlockingQueue，设置合适的容量。

**IO 密集型任务**：可选用无界队列搭配较大的线程数，但需警惕内存风险。LinkedBlockingQueue 是常用选择，但要注意控制任务提交速度，避免内存溢出。

**实时性要求高的任务**：SynchronousQueue 是理想选择，它不会缓存任务，直接传递给工作线程执行，适合需要立即处理的任务。

**任务优先级管理**：当任务具有不同优先级时，PriorityBlockingQueue 能够保证高优先级任务优先执行，提高系统响应速度。

### 2.2 线程复用原理

线程复用是 ThreadPoolExecutor 实现高性能的关键机制。通过复用已创建的线程执行新任务，避免了频繁创建和销毁线程带来的开销。

#### 2.2.1 线程复用机制详解

线程池的核心逻辑是 "预创建线程 + 任务缓存 + 动态调度"[(8)](https://blog.csdn.net/m0_72765822/article/details/154703539)。在任务提交前就预先创建若干线程放入池中，任务执行时直接从池中取可用线程运行，减少了线程创建和销毁的开销[(113)](https://www.cnblogs.com/xuchangqing/articles/19128554)。

线程复用的实现原理如下：



1. 线程池在初始化时创建一定数量的核心线程（corePoolSize）

2. 当有任务提交时，优先使用核心线程执行

3. 核心线程都在工作时，任务进入队列等待

4. 任务执行完毕后，线程不会销毁，而是从 workQueue 获取下一个任务（runWorker → getTask 循环），直到线程池关闭或线程被回收[(113)](https://www.cnblogs.com/xuchangqing/articles/19128554)

工作线程（Worker）通过循环从队列获取任务并执行，实现线程复用[(14)](https://blog.csdn.net/ZuanShi1111/article/details/151324104)。Worker 线程在创建时会绑定一个任务，在执行完该任务后，会不断从任务队列中获取新的任务并执行[(14)](https://blog.csdn.net/ZuanShi1111/article/details/151324104)。

#### 2.2.2 线程池的三级缓冲策略

Java 线程池的执行流程本质上是生产者 - 消费者模型的优化实现，其核心设计思想是通过三级缓冲实现资源弹性分配[(63)](https://juejin.cn/post/7537644857382535177)。这三级缓冲分别是：



1. **核心线程池（corePool）**：保持存活的常驻线程，即使处于空闲状态也不会被销毁（除非设置 allowCoreThreadTimeOut）[(3)](https://cloud.tencent.com.cn/developer/article/2552729)

2. **工作队列（workQueue）**：用于存储等待执行的任务，必须是 BlockingQueue 实现[(3)](https://cloud.tencent.com.cn/developer/article/2552729)

3. **临时线程池**：当核心线程都在工作且队列已满时，会创建临时线程执行任务，超出 maximumPoolSize 则触发拒绝策略[(3)](https://cloud.tencent.com.cn/developer/article/2552729)

这种三级缓冲策略的优势在于：



* 核心线程保证了基本的处理能力

* 工作队列提供了任务缓冲，平滑流量波动

* 临时线程应对突发负载，提高系统弹性

* 拒绝策略提供了过载保护，防止系统崩溃

### 2.3 拒绝策略设计

当线程池和工作队列都满时，新提交的任务需要通过拒绝策略来处理。拒绝策略是线程池的过载保护机制，确保系统在高负载下仍能稳定运行。

#### 2.3.1 内置拒绝策略分析

ThreadPoolExecutor 提供了四种内置的拒绝策略，均实现了 RejectedExecutionHandler 接口：



1. **AbortPolicy（中止策略）**

* 行为：直接抛出 RejectedExecutionException 异常[(28)](https://blog.51cto.com/vipstone/14086063)

* 特点：这是默认策略，确保任务不被静默丢弃，适合需要立即知道任务执行失败的场景

* 使用场景：关键业务流程，如金融交易、订单处理等

1. **CallerRunsPolicy（调用者运行策略）**

* 行为：将任务退回给提交任务的线程（即调用 execute () 的线程），由该线程直接执行任务[(28)](https://blog.51cto.com/vipstone/14086063)

* 特点：相当于同步执行，可降低提交速度，提供简单的反馈控制机制[(91)](https://tomcat.apache.org/tomcat-8.5-doc/api/org/apache/tomcat/util/threads/ThreadPoolExecutor.html)

* 使用场景：电商秒杀、限流场景，通过让调用者线程执行来减缓提交速度

1. **DiscardPolicy（丢弃策略）**

* 行为：静默丢弃被拒绝的任务，不抛出异常，也不执行任务[(28)](https://blog.51cto.com/vipstone/14086063)

* 特点：任务会被无声无息地丢弃

* 使用场景：非关键任务，如日志记录、监控数据采集等

1. **DiscardOldestPolicy（丢弃最旧策略）**

* 行为：丢弃工作队列中等待时间最长的任务（即队列头部的任务），然后重新尝试提交当前任务[(28)](https://blog.51cto.com/vipstone/14086063)

* 特点：通过丢弃旧任务来为新任务腾出空间

* 使用场景：实时数据处理，优先处理最新数据

#### 2.3.2 拒绝策略选择与扩展

拒绝策略的选择需要根据具体业务场景来决定：

**高可用性要求场景**：在电商秒杀场景中，可采用 CallerRunsPolicy 保证系统不被压垮，通过让调用者线程执行任务来实现流量削峰。

**日志处理场景**：DiscardPolicy 可避免阻塞主流程，当日志系统繁忙时，丢弃部分日志任务不会影响核心业务的运行。

**金融交易系统**：需要结合降级策略自定义拒绝逻辑，可能将任务写入消息队列或数据库，后续重试。

用户还可以通过实现 RejectedExecutionHandler 接口来自定义拒绝策略。例如，可以将拒绝的任务保存到持久化存储中，或者发送告警通知运维人员。

## 3. 实现细节深度剖析

### 3.1 状态管理机制

ThreadPoolExecutor 的状态管理是其实现的核心，通过精巧的设计实现了高效的并发控制。

#### 3.1.1 线程池状态定义与转换

ThreadPoolExecutor 定义了五种状态，这些状态管理着线程池的任务处理和行为[(35)](https://blog.csdn.net/m0_61600773/article/details/146064805)：



1. **RUNNING（运行中）**

* 状态值：-1 << COUNT\_BITS（高 3 位为 111）

* 含义：线程池的初始状态，具备 "接收新任务" 和 "执行队列中任务" 的能力

* 对任务的处理：接收新提交的任务；自动执行阻塞队列中堆积的任务

1. **SHUTDOWN（关闭中）**

* 状态值：0 << COUNT\_BITS（高 3 位为 000）

* 含义：线程池进入 "停止接收新任务，但继续执行队列中已有任务" 的过渡状态

* 对任务的处理：拒绝接收新任务；继续执行阻塞队列中已有的任务

* 触发方式：调用 shutdown () 方法

1. **STOP（停止）**

* 状态值：1 << COUNT\_BITS（高 3 位为 001）

* 含义：线程池进入 "强制停止" 状态，放弃所有未执行任务

* 对任务的处理：拒绝接收新任务；清空阻塞队列中未执行的任务；中断正在执行任务的工作线程

* 触发方式：调用 shutdownNow () 方法

1. **TIDYING（整理中）**

* 状态值：2 << COUNT\_BITS（高 3 位为 010）

* 含义：线程池的 "过渡状态"，表示所有任务已执行完毕、所有工作线程已销毁

* 触发条件：从 SHUTDOWN 进入：当任务队列和线程池均为空时；从 STOP 进入：当所有工作线程终止后[(35)](https://blog.csdn.net/m0_61600773/article/details/146064805)

* 后续动作：会自动调用 terminated () 钩子方法

1. **TERMINATED（已终止）**

* 状态值：3 << COUNT\_BITS（高 3 位为 011）

* 含义：线程池的最终状态，表示 "整理阶段" 已完成，线程池彻底销毁

* 触发条件：terminated () 方法执行完毕

状态转换遵循严格的规则，只能按照以下路径进行：



```
RUNNING → SHUTDOWN → TIDYING → TERMINATED

RUNNING → STOP → TIDYING → TERMINATED
```

#### 3.1.2 ctl 变量的位运算设计

ThreadPoolExecutor 使用一个 AtomicInteger 变量 ctl 同时存储线程池状态（runState）和工作线程数（workerCount），高 3 位表示状态，低 29 位表示线程数[(42)](https://blog.csdn.net/2301_78447438/article/details/150469911)。

**ctl 变量的设计细节**：



* COUNT\_BITS = 29（int 类型共 32 位，低 29 位存线程数）

* CAPACITY = (1 << COUNT\_BITS) - 1 = 536,870,911（线程数的最大上限）

**状态判断方法**：



```
private static boolean isRunning(int c) {

&#x20;   return c < SHUTDOWN;  // 等价于 (c & \~CAPACITY) == RUNNING

}

private static int runStateOf(int c) {

&#x20;   return c & \~CAPACITY;  // 屏蔽低29位

}
```

**线程数操作方法**：



```
private static int workerCountOf(int c) {

&#x20;   return c & CAPACITY;  // 屏蔽高3位

}

private static int ctlOf(int rs, int wc) {

&#x20;   return rs | wc;  // 组合状态和线程数

}
```

这种设计的优势在于：



* 空间效率：仅需 4 字节，相比传统方案（2 个 int 变量需 8 字节）节省 50% 内存

* 原子性保证：通过 AtomicInteger 的 CAS 操作，确保状态和线程数的原子性更新

* 性能优势：状态判断只需一次位运算（纳秒级），避免了传统方案的两次内存访问 + 锁检查

### 3.2 任务调度与执行流程

ThreadPoolExecutor 的任务调度流程体现了其设计的精巧性和高效性。

#### 3.2.1 execute 方法流程解析

execute 方法是 ThreadPoolExecutor 对外提供的用于提交任务的核心接口[(62)](https://blog.csdn.net/feiying101/article/details/138258480)。其执行流程如下：



```
public void execute(Runnable command) {

&#x20;   if (command == null)

&#x20;       throw new NullPointerException();

&#x20;  &#x20;

&#x20;   int c = ctl.get();

&#x20;  &#x20;

&#x20;   // 步骤1：尝试创建核心线程

&#x20;   if (workerCountOf(c) < corePoolSize) {

&#x20;       if (addWorker(command, true))

&#x20;           return;

&#x20;       c = ctl.get();

&#x20;   }

&#x20;  &#x20;

&#x20;   // 步骤2：尝试任务入队

&#x20;   if (isRunning(c) && workQueue.offer(command)) {

&#x20;       int recheck = ctl.get();

&#x20;       if (!isRunning(recheck) && remove(command))

&#x20;           reject(command);

&#x20;       else if (workerCountOf(recheck) == 0)

&#x20;           addWorker(null, false);

&#x20;   }

&#x20;  &#x20;

&#x20;   // 步骤3：尝试创建非核心线程

&#x20;   else if (!addWorker(command, false))

&#x20;       // 步骤4：执行拒绝策略

&#x20;       reject(command);

}
```

**流程分析**：



1. **核心线程优先策略**：线程池优先使用核心线程执行任务。当工作线程数小于 corePoolSize 时，立即创建新工作线程处理任务。

2. **任务入队机制**：如果核心线程已满，线程池尝试将任务放入阻塞队列。入队成功后，会重新获取线程池状态控制变量，进行二次检查：

* 如果线程池已经关闭（可能其他地方执行了 shutdown () 或 shutdownNow ()），则移除任务并执行拒绝策略

* 如果线程池为 RUNNING 状态，则检查线程池中线程数量是否为 0，如果为 0，则创建无初始任务的非核心线程

1. **非核心线程创建**：当队列已满且工作线程数小于 maximumPoolSize 时，创建非核心线程处理任务。

2. **拒绝策略触发**：当队列满且线程数达到 maximumPoolSize 后，新提交的任务将触发拒绝策略。

#### 3.2.2 任务执行的三级处理机制

ThreadPoolExecutor 的任务执行遵循 "核心线程 → 任务队列 → 临时线程 → 拒绝策略" 的黄金法则[(49)](https://blog.csdn.net/qq_59219765/article/details/155464321)。这种三级处理机制确保了资源的合理利用和系统的稳定性。

**三级处理流程详解**：



1. **第一级：核心线程处理**

* 核心线程是线程池的常驻力量，即使空闲也不会被销毁（除非设置 allowCoreThreadTimeOut）

* 新任务提交时，线程池首先检查当前工作线程数是否小于 corePoolSize

* 若是，则立即创建新工作线程处理该任务

1. **第二级：工作队列缓冲**

* 当核心线程都在工作时，新任务进入工作队列等待

* 队列的类型和容量决定了线程池的缓冲能力

* 无界队列（如 LinkedBlockingQueue）理论上可以无限缓冲，但存在内存溢出风险

* 有界队列（如 ArrayBlockingQueue）在队列满时会触发下一级处理

1. **第三级：临时线程扩展**

* 当队列已满且工作线程数小于 maximumPoolSize 时，创建临时线程（非核心线程）

* 临时线程在空闲时间超过 keepAliveTime 后会被回收

* 最大线程数限制了系统资源的使用上限

1. **拒绝策略保护**

* 当线程数达到 maximumPoolSize 且队列已满时，触发拒绝策略

* 拒绝策略提供了多种处理方式，确保系统不会因为过载而崩溃

### 3.3 线程创建与管理

线程的创建和管理是 ThreadPoolExecutor 实现线程复用的关键环节。

#### 3.3.1 Worker 类设计与实现

Worker 类是 ThreadPoolExecutor 的核心内部类，它封装了线程和任务执行逻辑[(59)](https://juejin.cn/post/7493786100445888524)：



```
private final class Worker extends AbstractQueuedSynchronizer implements Runnable {

&#x20;   final Thread thread;       // 工作线程

&#x20;   Runnable firstTask;        // 第一个任务，可能为null

&#x20;  &#x20;

&#x20;   Worker(Runnable firstTask) {

&#x20;       setState(-1);  // 禁止中断直到runWorker

&#x20;       this.firstTask = firstTask;

&#x20;       this.thread = getThreadFactory().newThread(this);

&#x20;   }

&#x20;  &#x20;

&#x20;   public void run() {

&#x20;       runWorker(this);

&#x20;   }

&#x20;  &#x20;

&#x20;   // 实现简单的不可重入锁

&#x20;   protected boolean isHeldExclusively() {

&#x20;       return getState() != 0;

&#x20;   }

&#x20;  &#x20;

&#x20;   protected boolean tryAcquire(int unused) {

&#x20;       if (compareAndSetState(0, 1)) {

&#x20;           setExclusiveOwnerThread(Thread.currentThread());

&#x20;           return true;

&#x20;       }

&#x20;       return false;

&#x20;   }

&#x20;  &#x20;

&#x20;   protected boolean tryRelease(int unused) {

&#x20;       setExclusiveOwnerThread(null);

&#x20;       setState(0);

&#x20;       return true;

&#x20;   }

}
```

**Worker 类的设计特点**：



1. **双重角色设计**：Worker 既是任务执行单元（实现 Runnable），又是同步控制单元（继承 AbstractQueuedSynchronizer）。这种设计实现了任务执行和锁控制的统一。

2. **线程创建**：Worker 在构造时使用线程工厂创建线程，线程工厂可以自定义线程的名称、优先级、守护状态等属性。

3. **锁机制**：Worker 通过 AQS 实现了一个简单的不可重入锁，用于在执行任务前后的状态保护。

#### 3.3.2 addWorker 方法分析

addWorker 方法是 ThreadPoolExecutor 中用于添加新工作线程的关键方法[(62)](https://blog.csdn.net/feiying101/article/details/138258480)：



```
private boolean addWorker(Runnable firstTask, boolean core) {

&#x20;   retry:

&#x20;   for (int c = ctl.get();;) {

&#x20;       int rs = runStateOf(c);

&#x20;      &#x20;

&#x20;       // 状态合法性验证

&#x20;       if (rs >= SHUTDOWN &&&#x20;

&#x20;           (rs >= STOP || firstTask != null || workQueue.isEmpty()))

&#x20;           return false;

&#x20;      &#x20;

&#x20;       for (;;) {

&#x20;           int wc = workerCountOf(c);

&#x20;           if (wc >= ((core ? corePoolSize : maximumPoolSize) & COUNT\_MASK))

&#x20;               return false;

&#x20;          &#x20;

&#x20;           if (compareAndIncrementWorkerCount(c))

&#x20;               break retry;

&#x20;          &#x20;

&#x20;           c = ctl.get();

&#x20;           if (runStateOf(c) != rs)

&#x20;               continue retry;

&#x20;       }

&#x20;   }

&#x20;  &#x20;

&#x20;   boolean workerStarted = false;

&#x20;   boolean workerAdded = false;

&#x20;   Worker w = null;

&#x20;   try {

&#x20;       w = new Worker(firstTask);

&#x20;       final Thread t = w.thread;

&#x20;       if (t != null) {

&#x20;           final ReentrantLock mainLock = this.mainLock;

&#x20;           mainLock.lock();

&#x20;           try {

&#x20;               int c = ctl.get();

&#x20;               int rs = runStateOf(c);

&#x20;              &#x20;

&#x20;               if (isRunning(c) ||&#x20;

&#x20;                   (rs < STOP && firstTask == null)) {

&#x20;                   if (t.getState() != Thread.State.NEW)

&#x20;                       throw new IllegalThreadStateException();

&#x20;                   workers.add(w);

&#x20;                   int s = workers.size();

&#x20;                   if (s > largestPoolSize)

&#x20;                       largestPoolSize = s;

&#x20;                   workerAdded = true;

&#x20;               }

&#x20;           } finally {

&#x20;               mainLock.unlock();

&#x20;           }

&#x20;          &#x20;

&#x20;           if (workerAdded) {

&#x20;               t.start();

&#x20;               workerStarted = true;

&#x20;           }

&#x20;       }

&#x20;   } finally {

&#x20;       if (!workerStarted)

&#x20;           addWorkerFailed(w);

&#x20;   }

&#x20;   return workerStarted;

}
```

**addWorker 方法的关键逻辑**：



1. **双重循环机制**：外层循环处理状态变更，内层循环处理线程计数。这种设计确保了在复杂的并发环境下，线程创建操作的原子性和正确性。

2. **状态检查**：在创建 Worker 之前，会进行严格的状态检查，确保线程池处于允许创建新线程的状态。

3. **CAS 原子操作**：使用 compareAndIncrementWorkerCount 方法通过 CAS 操作原子性地增加工作线程数，确保线程计数增加的安全性。

4. **线程安全的 Worker 添加**：在 mainLock 的保护下，将新创建的 Worker 添加到 workers 集合中，确保线程安全。

5. **线程启动**：在 Worker 添加成功后，调用 t.start () 启动线程，线程将执行 Worker 的 run 方法，进而调用 runWorker 方法。

#### 3.3.3 runWorker 与 getTask 方法

runWorker 方法是线程执行任务的核心方法：



```
final void runWorker(Worker w) {

&#x20;   Thread wt = Thread.currentThread();

&#x20;   Runnable task = w.firstTask;

&#x20;   w.firstTask = null;

&#x20;   w.unlock(); // 允许中断

&#x20;  &#x20;

&#x20;   boolean completedAbruptly = true;

&#x20;   try {

&#x20;       while (task != null || (task = getTask()) != null) {

&#x20;           w.lock();

&#x20;          &#x20;

&#x20;           // 中断处理逻辑...

&#x20;          &#x20;

&#x20;           try {

&#x20;               beforeExecute(wt, task);

&#x20;               try {

&#x20;                   task.run();

&#x20;                   afterExecute(task, null);

&#x20;               } catch (Throwable ex) {

&#x20;                   afterExecute(task, ex);

&#x20;                   throw ex;

&#x20;               }

&#x20;           } finally {

&#x20;               task = null;

&#x20;               w.completedTasks++;

&#x20;               w.unlock();

&#x20;           }

&#x20;       }

&#x20;       completedAbruptly = false;

&#x20;   } finally {

&#x20;       processWorkerExit(w, completedAbruptly);

&#x20;   }

}
```

**runWorker 方法的执行流程**：



1. **初始任务执行**：首先执行构造 Worker 时传入的 firstTask（如果有）。

2. **循环获取任务**：通过 getTask () 方法从队列中获取任务，形成一个无限循环，直到 getTask () 返回 null。

3. **钩子方法调用**：在任务执行前后分别调用 beforeExecute 和 afterExecute 钩子方法，可用于自定义扩展。

4. **异常处理**：任务执行过程中的异常会被捕获，并通过 afterExecute 方法处理。

getTask 方法负责从工作队列中获取任务，并实现了线程的超时回收机制：



```
private Runnable getTask() {

&#x20;   boolean timedOut = false;

&#x20;  &#x20;

&#x20;   for (;;) {

&#x20;       int c = ctl.get();

&#x20;       int rs = runStateOf(c);

&#x20;      &#x20;

&#x20;       if (rs >= SHUTDOWN && (rs >= STOP || workQueue.isEmpty())) {

&#x20;           decrementWorkerCount();

&#x20;           return null;

&#x20;       }

&#x20;      &#x20;

&#x20;       int wc = workerCountOf(c);

&#x20;      &#x20;

&#x20;       boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

&#x20;      &#x20;

&#x20;       if ((wc > maximumPoolSize || (timed && timedOut)) && (wc > 1 || workQueue.isEmpty())) {

&#x20;           if (compareAndDecrementWorkerCount(c))

&#x20;               return null;

&#x20;           continue;

&#x20;       }

&#x20;      &#x20;

&#x20;       try {

&#x20;           Runnable r = timed ?

&#x20;               workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :

&#x20;               workQueue.take();

&#x20;           if (r != null)

&#x20;               return r;

&#x20;           timedOut = true;

&#x20;       } catch (InterruptedException retry) {

&#x20;           timedOut = false;

&#x20;       }

&#x20;   }

}
```

**getTask 方法的关键逻辑**：



1. **状态检查**：检查线程池状态，在 SHUTDOWN 或 STOP 状态下，如果队列为空，返回 null，导致工作线程退出。

2. **超时机制**：

* 对于非核心线程（wc > corePoolSize），默认启用超时机制

* 如果设置了 allowCoreThreadTimeOut 为 true，核心线程也会启用超时机制

* 使用 poll 方法尝试获取任务，超时后返回 null

1. **线程回收条件**：当线程数超过 maximumPoolSize，或者线程空闲时间超过 keepAliveTime（且 timed 为 true），并且满足其他条件（如 wc > 1 或队列为空）时，线程会被回收。

2. **异常处理**：InterruptedException 会被捕获，重置 timedOut 标志，继续循环。

### 3.4 线程池关闭机制

ThreadPoolExecutor 提供了两种关闭方式，满足不同场景的需求。

#### 3.4.1 shutdown 与 shutdownNow 对比

**shutdown 方法（平滑关闭）**：



```
public void shutdown() {

&#x20;   final ReentrantLock mainLock = this.mainLock;

&#x20;   mainLock.lock();

&#x20;   try {

&#x20;       advanceRunState(SHUTDOWN);

&#x20;       interruptIdleWorkers();

&#x20;       onShutdown();

&#x20;   } finally {

&#x20;       mainLock.unlock();

&#x20;   }

&#x20;   tryTerminate();

}
```

平滑关闭的特点：



* 切换到 SHUTDOWN 状态，不再接受新任务，但会执行完队列中的任务

* 只中断空闲线程，正在执行任务的线程不会被中断

* 调用 onShutdown () 钩子方法（可重写）

* 调用 tryTerminate () 尝试进入 TIDYING 状态

**shutdownNow 方法（立即关闭）**：



```
public List\<Runnable> shutdownNow() {

&#x20;   List\<Runnable> tasks;

&#x20;   final ReentrantLock mainLock = this.mainLock;

&#x20;   mainLock.lock();

&#x20;   try {

&#x20;       advanceRunState(STOP);

&#x20;       interruptWorkers();

&#x20;       tasks = drainQueue();

&#x20;   } finally {

&#x20;       mainLock.unlock();

&#x20;   }

&#x20;   tryTerminate();

&#x20;   return tasks;

}
```

立即关闭的特点：



* 切换到 STOP 状态，不再接受新任务，也不执行队列中的任务，并且中断执行中的任务

* 中断所有工作线程（无论是否空闲）

* 清空阻塞队列，返回未执行的任务列表

* 调用 tryTerminate () 尝试进入 TIDYING 状态

#### 3.4.2 优雅关闭的实现策略

优雅关闭是指在关闭线程池时，确保已提交的任务都能得到执行，避免任务丢失。实现优雅关闭的策略如下：



1. **先调用 shutdown ()**：让线程池停止接收新任务，继续执行队列中的任务。

2. **等待任务执行完成**：可以使用 awaitTermination 方法等待线程池终止：



```
try {

&#x20;   if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {

&#x20;       executor.shutdownNow();

&#x20;       if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {

&#x20;           System.err.println("线程池未能正常终止");

&#x20;       }

&#x20;   }

} catch (InterruptedException e) {

&#x20;   executor.shutdownNow();

&#x20;   Thread.currentThread().interrupt();

}
```



1. **强制关闭处理**：如果等待超时，调用 shutdownNow () 强制关闭，并再次等待。

2. **资源清理**：在 terminated () 钩子方法中执行资源清理操作。

这种策略的优势在于：



* 保证了任务的完整性，不会丢失已提交的任务

* 给了线程池足够的时间来完成现有工作

* 通过超时机制避免了无限等待

* 提供了强制关闭的兜底方案

## 4. 高级功能特性

### 4.1 线程池监控功能

ThreadPoolExecutor 提供了丰富的监控接口，能够实时了解线程池的运行状态。

#### 4.1.1 监控指标体系

ThreadPoolExecutor 提供了以下核心监控方法[(70)](https://m.php.cn/faq/1592387.html)：



1. **线程相关指标**

* **getActiveCount()**：返回当前正在执行任务的线程数[(70)](https://m.php.cn/faq/1592387.html)

* **getPoolSize()**：返回当前线程池中线程的总数，包括空闲和活动的线程[(75)](https://www.cnblogs.com/gongchengship/p/18455486)

* **getCorePoolSize()**：返回线程池的核心线程数[(71)](https://blog.csdn.net/qq_41893505/article/details/146219787)

* **getMaximumPoolSize()**：返回线程池的最大线程数[(71)](https://blog.csdn.net/qq_41893505/article/details/146219787)

1. **任务相关指标**

* **getCompletedTaskCount()**：返回已完成的任务总数[(70)](https://m.php.cn/faq/1592387.html)

* **getTaskCount()**：返回已提交的总任务数（包括正在执行和等待的）[(70)](https://m.php.cn/faq/1592387.html)

1. **队列相关指标**

* **getQueue().size()**：返回等待队列中的任务数量[(70)](https://m.php.cn/faq/1592387.html)

* **getQueue().remainingCapacity()**：返回队列的剩余容量（仅对有界队列有效）

1. **状态相关指标**

* **getLargestPoolSize()**：返回线程池曾经达到的最大线程数

* **getKeepAliveTime(TimeUnit unit)**：返回空闲线程的存活时间

这些监控指标的作用：



* **活跃线程数**反映系统当前的负载情况[(73)](https://gaga.plus/app/thread-pool/chapter-13-monitoring/)

* **队列长度**反映系统的任务积压情况[(73)](https://gaga.plus/app/thread-pool/chapter-13-monitoring/)

* **已完成任务数与总任务数的比例**可以评估系统的处理进度

#### 4.1.2 监控数据的线程安全性

ThreadPoolExecutor 的监控方法返回的都是近似值，因为在计算过程中，任务和线程的状态可能动态变化。这些方法的实现考虑了并发安全性：



1. **原子变量的使用**：如 completedTaskCount 使用 AtomicLong 类型，保证了计数的原子性。

2. **快照机制**：许多指标是通过获取当前状态的快照来实现的，例如 getPoolSize () 方法：



```
public int getPoolSize() {

&#x20;   final ReentrantLock mainLock = this.mainLock;

&#x20;   mainLock.lock();

&#x20;   try {

&#x20;       return workers.size();

&#x20;   } finally {

&#x20;       mainLock.unlock();

&#x20;   }

}
```



1. **近似值说明**：由于线程池的状态是动态变化的，这些监控方法返回的值可能不是精确的，但能够反映线程池的大致状态。

在实际应用中，建议：



* 不要依赖监控数据进行关键业务决策

* 结合多个指标进行综合分析

* 使用监控系统定期采集数据，形成趋势分析

### 4.2 扩展钩子机制

ThreadPoolExecutor 使用模板方法模式，提供了 beforeExecute、afterExecute 和 terminated 扩展方法[(78)](https://blog.csdn.net/danielzhou888/article/details/84074366)，允许用户在任务执行的不同阶段插入自定义逻辑。

#### 4.2.1 任务执行钩子方法

**beforeExecute 方法**：



```
protected void beforeExecute(Thread t, Runnable r) {

&#x20;   // 默认实现不执行任何操作

}
```



* 调用时机：在给定线程中执行给定 Runnable 之前调用[(82)](https://www.cnblogs.com/iuyy/p/13622154.html)

* 调用线程：执行任务的线程

* 用途：可用于重新初始化 ThreadLocals、更新日志记录、统计任务开始时间等

**afterExecute 方法**：



```
protected void afterExecute(Runnable r, Throwable t) {

&#x20;   // 默认实现不执行任何操作

}
```



* 调用时机：在任务执行完成后调用，无论是正常完成还是抛出异常[(77)](https://blog.csdn.net/jiaoshi5167/article/details/129250662)

* 参数说明：r 是执行的任务，t 是任务执行过程中抛出的异常（如果有）

* 用途：可用于统计任务执行时间、记录异常信息、清理资源等

**钩子方法的使用示例**：



```
class MonitoringThreadPool extends ThreadPoolExecutor {

&#x20;   private final AtomicLong totalExecutionTime = new AtomicLong();

&#x20;   private final AtomicInteger taskCount = new AtomicInteger();

&#x20;  &#x20;

&#x20;   protected void beforeExecute(Thread t, Runnable r) {

&#x20;       super.beforeExecute(t, r);

&#x20;       System.out.printf("Thread %s about to execute task: %s%n", t.getName(), r);

&#x20;   }

&#x20;  &#x20;

&#x20;   protected void afterExecute(Runnable r, Throwable t) {

&#x20;       super.afterExecute(r, t);

&#x20;       long endTime = System.currentTimeMillis();

&#x20;       // 假设在beforeExecute中记录了开始时间

&#x20;       long executionTime = endTime - startTime.get();

&#x20;       totalExecutionTime.addAndGet(executionTime);

&#x20;       taskCount.incrementAndGet();

&#x20;      &#x20;

&#x20;       if (t != null) {

&#x20;           System.err.printf("Task %s failed with exception: %s%n", r, t);

&#x20;       } else {

&#x20;           System.out.printf("Task %s completed in %d ms%n", r, executionTime);

&#x20;       }

&#x20;      &#x20;

&#x20;       // 每100个任务输出一次统计信息

&#x20;       if (taskCount.get() % 100 == 0) {

&#x20;           System.out.printf("Average execution time: %d ms%n",&#x20;

&#x20;                             totalExecutionTime.get() / taskCount.get());

&#x20;       }

&#x20;   }

}
```

#### 4.2.2 terminated 钩子方法

terminated 方法在线程池完成关闭时调用，也就是在所有任务都已经完成并且所有工作者线程也已经关闭后[(78)](https://blog.csdn.net/danielzhou888/article/details/84074366)：



```
protected void terminated() {

&#x20;   // 默认实现不执行任何操作

}
```

terminated 方法的用途：



1. **资源清理**：释放 Executor 在其生命周期里分配的各种资源[(78)](https://blog.csdn.net/danielzhou888/article/details/84074366)

2. **通知机制**：执行发送通知、记录日志等操作[(78)](https://blog.csdn.net/danielzhou888/article/details/84074366)

3. **统计汇总**：收集 finalize 统计信息，如打印线程池的执行统计

使用 terminated 方法的示例：



```
class CleanupThreadPool extends ThreadPoolExecutor {

&#x20;   private final List\<Resource> resources = new ArrayList<>();

&#x20;  &#x20;

&#x20;   protected void terminated() {

&#x20;       super.terminated();

&#x20;       System.out.println("ThreadPool terminated, cleaning up resources...");

&#x20;       for (Resource resource : resources) {

&#x20;           resource.close();

&#x20;       }

&#x20;       System.out.println("All resources cleaned up.");

&#x20;   }

&#x20;  &#x20;

&#x20;   public void registerResource(Resource resource) {

&#x20;       resources.add(resource);

&#x20;   }

}
```

### 4.3 预启动线程与动态调整

ThreadPoolExecutor 提供了预启动线程和动态调整线程池参数的功能，增强了其灵活性和性能。

#### 4.3.1 预启动线程功能

预启动线程功能允许在线程池创建后立即启动核心线程，而不是等待任务到达时才创建。

**prestartCoreThread 方法**：



```
public boolean prestartCoreThread() {

&#x20;   // 启动一个核心线程

}
```



* 功能：启动一个核心线程，使其空闲等待工作

* 返回值：如果成功启动了一个线程则返回 true

* 特点：这会覆盖默认的仅在执行新任务时才启动核心线程的策略

**prestartAllCoreThreads 方法**：



```
public int prestartAllCoreThreads() {

&#x20;   // 启动所有核心线程

}
```



* 功能：启动所有核心线程，使其空闲等待工作

* 返回值：启动的线程数

* 特点：同样会覆盖默认的线程启动策略

**预启动线程的使用场景**：



1. **冷启动优化**：减少首次任务提交时的延迟，因为线程已经处于就绪状态

2. **预热场景**：如果线程池使用非空队列构造，可能希望预启动线程[(85)](https://blog.csdn.net/m0_37148920/article/details/98891934)

3. **响应时间敏感的应用**：如实时处理系统，需要最快的响应速度

使用示例：



```
ThreadPoolExecutor executor = new ThreadPoolExecutor(

&#x20;   5, 10, 60, TimeUnit.SECONDS,

&#x20;   new LinkedBlockingQueue<>(100),

&#x20;   Executors.defaultThreadFactory(),

&#x20;   new ThreadPoolExecutor.AbortPolicy()

);

// 预启动所有核心线程

executor.prestartAllCoreThreads();

System.out.println("All core threads are started and ready.");
```

#### 4.3.2 线程池参数动态调整

ThreadPoolExecutor 支持运行时动态调整多个参数，提供了极大的灵活性。

**可动态调整的参数**：



1. **核心线程数**：



```
public void setCorePoolSize(int corePoolSize) {

&#x20;   // 设置核心线程数

}
```



* 可以增加或减少核心线程数

* 如果增加，可能会立即创建新线程

* 如果减少，超过新值的核心线程可能会被终止

1. **最大线程数**：



```
public void setMaximumPoolSize(int maximumPoolSize) {

&#x20;   // 设置最大线程数

}
```



* 可以限制线程池能创建的最大线程数

* 如果当前线程数超过新的最大值，多余的线程会被终止

1. **存活时间**：



```
public void setKeepAliveTime(long time, TimeUnit unit) {

&#x20;   // 设置线程空闲存活时间

}
```



* 调整空闲线程的存活时间

* 对非核心线程有效，除非设置了 allowCoreThreadTimeOut

1. **核心线程超时设置**：



```
public void allowCoreThreadTimeOut(boolean value) {

&#x20;   // 设置核心线程是否允许超时

}
```



* 默认情况下，核心线程不会因为空闲而终止

* 设置为 true 后，核心线程也会在空闲超过 keepAliveTime 后被终止

**动态调整的使用示例**：



```
ThreadPoolExecutor executor = new ThreadPoolExecutor(

&#x20;   5, 10, 60, TimeUnit.SECONDS,

&#x20;   new LinkedBlockingQueue<>(100),

&#x20;   Executors.defaultThreadFactory(),

&#x20;   new ThreadPoolExecutor.AbortPolicy()

);

// 模拟根据负载动态调整线程池大小

ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

scheduler.scheduleAtFixedRate(() -> {

&#x20;   int queueSize = executor.getQueue().size();

&#x20;   int activeThreads = executor.getActiveCount();

&#x20;  &#x20;

&#x20;   if (queueSize > 50 && activeThreads < executor.getMaximumPoolSize()) {

&#x20;       // 任务积压，增加核心线程数

&#x20;       int newCore = Math.min(executor.getCorePoolSize() + 1, executor.getMaximumPoolSize());

&#x20;       executor.setCorePoolSize(newCore);

&#x20;       System.out.printf("Increased corePoolSize to %d%n", newCore);

&#x20;   } else if (queueSize < 10 && activeThreads > executor.getCorePoolSize()) {

&#x20;       // 负载降低，减少核心线程数

&#x20;       int newCore = Math.max(executor.getCorePoolSize() - 1, 2);

&#x20;       executor.setCorePoolSize(newCore);

&#x20;       System.out.printf("Decreased corePoolSize to %d%n", newCore);

&#x20;   }

}, 1, 1, TimeUnit.MINUTES);
```

**动态调整的注意事项**：



1. 调整核心线程数可能会立即创建或销毁线程，需要考虑线程创建和销毁的开销

2. 最大线程数的调整可能导致正在执行的线程被中断，需要谨慎使用

3. 存活时间的调整会影响所有空闲线程，包括正在等待任务的线程

4. 建议在调整参数前先评估系统负载，避免频繁调整带来的性能开销

### 4.4 线程工厂与拒绝策略的定制

ThreadPoolExecutor 的线程工厂和拒绝策略都支持高度定制，满足不同场景的需求。

#### 4.4.1 线程工厂的定制

线程工厂通过 ThreadFactory 接口定义：



```
public interface ThreadFactory {

&#x20;   Thread newThread(Runnable r);

}
```

**默认线程工厂的特点**：



* 如果未指定，使用 Executors.defaultThreadFactory ()[(16)](https://docs.oracle.com/javase/jp/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)

* 创建的线程都在同一个 ThreadGroup 内

* 具有相同的 NORM\_PRIORITY 优先级

* 为非守护线程

**自定义线程工厂的应用场景**：



1. **线程命名**：为线程设置有意义的名称，便于调试和监控

2. **线程优先级**：根据任务类型设置不同的优先级

3. **守护线程**：某些场景下需要使用守护线程

4. **异常处理**：设置线程的未捕获异常处理器

自定义线程工厂示例：



```
class NamedThreadFactory implements ThreadFactory {

&#x20;   private final String namePrefix;

&#x20;   private final AtomicInteger threadNumber = new AtomicInteger(1);

&#x20;   private final boolean daemon;

&#x20;   private final int priority;

&#x20;  &#x20;

&#x20;   public NamedThreadFactory(String namePrefix) {

&#x20;       this(namePrefix, false, Thread.NORM\_PRIORITY);

&#x20;   }

&#x20;  &#x20;

&#x20;   public NamedThreadFactory(String namePrefix, boolean daemon, int priority) {

&#x20;       this.namePrefix = namePrefix;

&#x20;       this.daemon = daemon;

&#x20;       this.priority = priority;

&#x20;   }

&#x20;  &#x20;

&#x20;   public Thread newThread(Runnable r) {

&#x20;       Thread t = new Thread(r, namePrefix + "-" + threadNumber.getAndIncrement());

&#x20;       t.setDaemon(daemon);

&#x20;       t.setPriority(priority);

&#x20;       t.setUncaughtExceptionHandler((thread, ex) -> {

&#x20;           System.err.printf("Thread %s died with exception: %s%n", thread.getName(), ex);

&#x20;       });

&#x20;       return t;

&#x20;   }

}

// 使用示例

ThreadPoolExecutor executor = new ThreadPoolExecutor(

&#x20;   5, 10, 60, TimeUnit.SECONDS,

&#x20;   new LinkedBlockingQueue<>(100),

&#x20;   new NamedThreadFactory("MyWorker-", false, Thread.NORM\_PRIORITY),

&#x20;   new ThreadPoolExecutor.AbortPolicy()

);
```

#### 4.4.2 拒绝策略的扩展

拒绝策略通过 RejectedExecutionHandler 接口定义：



```
public interface RejectedExecutionHandler {

&#x20;   void rejectedExecution(Runnable r, ThreadPoolExecutor executor);

}
```

**内置拒绝策略回顾**：



1. **AbortPolicy**：抛出 RejectedExecutionException

2. **CallerRunsPolicy**：由调用者线程执行任务

3. **DiscardPolicy**：静默丢弃任务

4. **DiscardOldestPolicy**：丢弃最旧的任务，重试提交当前任务

**自定义拒绝策略的示例**：



1. **日志记录策略**：



```
class LoggingRejectPolicy implements RejectedExecutionHandler {

&#x20;   private final Logger logger;

&#x20;  &#x20;

&#x20;   public LoggingRejectPolicy(Logger logger) {

&#x20;       this.logger = logger;

&#x20;   }

&#x20;  &#x20;

&#x20;   public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {

&#x20;       logger.warning(String.format("Task %s rejected from %s%n", r, executor));

&#x20;   }

}
```



1. **写入队列策略**：



```
class QueueRejectPolicy implements RejectedExecutionHandler {

&#x20;   private final BlockingQueue\<Runnable> backupQueue;

&#x20;  &#x20;

&#x20;   public QueueRejectPolicy(BlockingQueue\<Runnable> backupQueue) {

&#x20;       this.backupQueue = backupQueue;

&#x20;   }

&#x20;  &#x20;

&#x20;   public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {

&#x20;       try {

&#x20;           backupQueue.put(r);

&#x20;           System.out.println("Task moved to backup queue.");

&#x20;       } catch (InterruptedException e) {

&#x20;           Thread.currentThread().interrupt();

&#x20;           throw new RejectedExecutionException("Failed to move task to backup queue", e);

&#x20;       }

&#x20;   }

}
```



1. **延时重试策略**：



```
class RetryRejectPolicy implements RejectedExecutionHandler {

&#x20;   private final ScheduledExecutorService scheduler;

&#x20;   private final long delay;

&#x20;  &#x20;

&#x20;   public RetryRejectPolicy(ScheduledExecutorService scheduler, long delay, TimeUnit unit) {

&#x20;       this.scheduler = scheduler;

&#x20;       this.delay = unit.toMillis(delay);

&#x20;   }

&#x20;  &#x20;

&#x20;   public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {

&#x20;       scheduler.schedule(() -> {

&#x20;           try {

&#x20;               executor.execute(r);

&#x20;               System.out.println("Task retry succeeded.");

&#x20;           } catch (RejectedExecutionException e) {

&#x20;               System.out.println("Task retry failed again.");

&#x20;           }

&#x20;       }, delay, TimeUnit.MILLISECONDS);

&#x20;   }

}
```

## 5. 官方文档与源码分析指南

### 5.1 官方文档解读

Oracle 官方文档是学习 ThreadPoolExecutor 的权威资料，需要掌握正确的阅读方法。

#### 5.1.1 类文档结构分析

ThreadPoolExecutor 的官方文档包含以下重要部分：



1. **类声明**：



```
public class ThreadPoolExecutor extends AbstractExecutorService
```



* 继承自 AbstractExecutorService，实现了 ExecutorService 接口

* 直接已知子类：ScheduledThreadPoolExecutor

1. **类描述**：

* 说明 ThreadPoolExecutor 是一个 ExecutorService，使用池中的线程执行每个提交的任务

* 通常使用 Executors 工厂方法配置

* 解决了两个问题：减少任务调用开销；提供资源边界设定和管理方法

* 维护基本统计信息（如已完成任务数）

1. **构造方法**：



```
public ThreadPoolExecutor(int corePoolSize,

&#x20;                         int maximumPoolSize,

&#x20;                         long keepAliveTime,

&#x20;                         TimeUnit unit,

&#x20;                         BlockingQueue\<Runnable> workQueue,

&#x20;                         ThreadFactory threadFactory,

&#x20;                         RejectedExecutionHandler handler)
```



* 详细说明了每个参数的含义和作用

* 抛出的异常：IllegalArgumentException、NullPointerException

1. **核心方法**：

* execute (Runnable command)：提交任务执行

* shutdown ()：启动有序关闭

* shutdownNow ()：尝试停止所有任务

* 各种 getter 方法：获取线程池状态和统计信息

1. **钩子方法**：

* beforeExecute(Thread, Runnable)

* afterExecute(Runnable, Throwable)

* terminated()

1. **线程工厂和拒绝策略接口**：

* ThreadFactory 接口

* RejectedExecutionHandler 接口

#### 5.1.2 重要方法文档说明

**execute 方法**：



```
public void execute(Runnable command)
```



* 作用：在未来某个时间执行给定的任务

* 参数：command - 要执行的任务

* 抛出：NullPointerException if command is null

* 说明：任务可能在新线程中执行，也可能在现有池线程中执行

* 注意：如果无法将任务提交执行（因为线程池已关闭或已达到容量），则根据配置的拒绝策略处理该任务

**shutdown 方法**：



```
public void shutdown()
```



* 作用：启动有序关闭，在此过程中不再接受新任务，但会执行已提交的任务（包括队列中的任务）

* 说明：调用后，isShutdown () 返回 true

* 不会等待任务执行完成

**shutdownNow 方法**：



```
public List\<Runnable> shutdownNow()
```



* 作用：尝试停止所有正在执行的任务，停止处理等待的任务，并返回等待执行的任务列表

* 返回：等待执行的任务列表

* 说明：调用后，isShutdown () 和 isTerminated () 可能不会立即返回 true

**awaitTermination 方法**：



```
public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException
```



* 作用：阻塞直到所有任务在关闭请求后完成执行，或发生超时，或当前线程被中断

* 参数：timeout - 等待的最长时间；unit - timeout 参数的时间单位

* 返回：如果所有任务在终止前完成，则返回 true；如果在终止前发生超时，则返回 false

* 抛出：InterruptedException if interrupted while waiting

### 5.2 核心源码分析

深入理解 ThreadPoolExecutor 的源码是掌握其实现原理的关键。

#### 5.2.1 关键类与方法

**核心类结构**：



1. **ThreadPoolExecutor 类**：

* 包含 ctl 原子变量、工作线程集合 workers、工作队列 workQueue 等核心字段

* 提供 execute、shutdown、shutdownNow 等公共方法

* 包含 addWorker、runWorker、getTask 等核心实现方法

1. **Worker 类**：

* 继承自 AbstractQueuedSynchronizer，实现 Runnable 接口

* 封装了工作线程 thread 和第一个任务 firstTask

* 实现了简单的不可重入锁机制

1. **状态管理相关**：

* ctl：AtomicInteger 类型，高 3 位表示状态，低 29 位表示线程数

* 5 个状态常量：RUNNING、SHUTDOWN、STOP、TIDYING、TERMINATED

* 位运算工具方法：runStateOf、workerCountOf、ctlOf

**关键方法列表**：



1. **任务提交**：

* execute (Runnable)：任务提交入口

* submit (Callable)：提交有返回值的任务

* invokeAll、invokeAny：批量提交任务

1. **线程管理**：

* addWorker (Runnable, boolean)：添加工作线程

* runWorker (Worker)：工作线程的核心执行逻辑

* getTask ()：从队列获取任务

* processWorkerExit (Worker, boolean)：处理工作线程退出

1. **状态控制**：

* shutdown ()：平滑关闭

* shutdownNow ()：立即关闭

* tryTerminate ()：尝试终止线程池

* advanceRunState (int)：推进运行状态

1. **监控方法**：

* getPoolSize ()：获取线程池大小

* getActiveCount ()：获取活跃线程数

* getCompletedTaskCount ()：获取已完成任务数

* getQueue ()：获取工作队列

#### 5.2.2 核心代码注释解读

以 ctl 变量和状态管理相关代码为例，解读关键注释：



```
// ctl是一个原子整数，用于存储线程池的状态(runState)和工作线程数(workerCount)

// 高3位存储runState，低29位存储workerCount

// COUNT\_BITS = 29，因为int是32位，我们需要保留3位给状态

private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));

// 计算count的位数（29）

private static final int COUNT\_BITS = Integer.SIZE - 3;

// 容量 = (1 << COUNT\_BITS) - 1 = 0b0001111111111111111111111111111 = 536,870,911

private static final int CAPACITY   = (1 << COUNT\_BITS) - 1;

// 线程池状态 - 高3位

private static final int RUNNING    = -1 << COUNT\_BITS; // 111...111000...000

private static final int SHUTDOWN   =  0 << COUNT\_BITS; // 000...000000...000

private static final int STOP       =  1 << COUNT\_BITS; // 001...001000...000

private static final int TIDYING    =  2 << COUNT\_BITS; // 010...010000...000

private static final int TERMINATED =  3 << COUNT\_BITS; // 011...011000...000

// 从ctl中提取运行状态

private static int runStateOf(int c)     { return c & \~CAPACITY; }

// 从ctl中提取工作线程数

private static int workerCountOf(int c)  { return c & CAPACITY; }

// 组合运行状态和工作线程数为ctl值

private static int ctlOf(int rs, int wc) { return rs | wc; }
```

**代码注释的重要性**：



1. 解释了 ctl 变量的设计思想：用一个变量存储两个信息

2. 说明了位运算的原理：高 3 位和低 29 位的划分

3. 定义了所有状态常量的值和含义

4. 提供了状态和线程数的提取方法

**runWorker 方法的关键注释**：



```
final void runWorker(Worker w) {

&#x20;   Thread wt = Thread.currentThread();

&#x20;   Runnable task = w.firstTask;

&#x20;   w.firstTask = null;

&#x20;   w.unlock(); // 允许中断，因为我们可能需要响应中断来退出

&#x20;  &#x20;

&#x20;   boolean completedAbruptly = true;

&#x20;   try {

&#x20;       // 循环获取并执行任务，直到getTask()返回null

&#x20;       while (task != null || (task = getTask()) != null) {

&#x20;           w.lock();

&#x20;          &#x20;

&#x20;           // 如果池状态 >= STOP，或者池状态是SHUTDOWN且任务为null，需要重新检查中断状态

&#x20;           if ((runStateAtLeast(ctl.get(), STOP) ||

&#x20;               (runStateAtLeast(ctl.get(), SHUTDOWN) && task == null)) &&

&#x20;               !w.isInterrupted()) {

&#x20;               w.interrupt();

&#x20;           }

&#x20;          &#x20;

&#x20;           try {

&#x20;               beforeExecute(wt, task); // 钩子方法 - 任务执行前

&#x20;               Throwable thrown = null;

&#x20;               try {

&#x20;                   task.run(); // 执行任务

&#x20;               } catch (RuntimeException x) {

&#x20;                   thrown = x; throw x;

&#x20;               } catch (Error x) {

&#x20;                   thrown = x; throw x;

&#x20;               } catch (Throwable x) {

&#x20;                   thrown = x; throw new Error(x);

&#x20;               } finally {

&#x20;                   afterExecute(task, thrown); // 钩子方法 - 任务执行后

&#x20;               }

&#x20;           } finally {

&#x20;               task = null;

&#x20;               w.completedTasks++;

&#x20;               w.unlock();

&#x20;           }

&#x20;       }

&#x20;       completedAbruptly = false;

&#x20;   } finally {

&#x20;       processWorkerExit(w, completedAbruptly); // 处理线程退出

&#x20;   }

}
```

**关键注释说明**：



1. 解释了 w.unlock () 的作用：允许线程响应中断

2. 说明了循环条件：只要有任务就继续执行

3. 详细解释了中断处理逻辑

4. 明确了钩子方法的调用时机

5. 说明了 completedAbruptly 标志的用途

## 6. 简化版实现指南

### 6.1 实现思路与步骤规划

实现一个简化版的 ThreadPoolExecutor 是深入理解其原理的最佳方式。建议采用渐进式实现策略。

#### 6.1.1 功能设计与架构规划

**简化版线程池的功能定位**：



* 实现核心功能：线程复用、任务队列、基本拒绝策略

* 简化复杂度：固定线程数，不支持动态调整

* 保证线程安全：使用合适的并发控制机制

* 提供监控接口：基本的状态和统计信息

**架构设计**：



1. **核心组件**：

* ThreadPool 类：线程池管理器

* Worker 类：工作线程

* BlockingQueue：任务队列

* RejectedPolicy：拒绝策略

1. **类图设计**：



```
ThreadPool

├── corePoolSize: int

├── workers: Set\<Worker>

├── workQueue: BlockingQueue\<Runnable>

├── rejectedPolicy: RejectedPolicy

├── isShutdown: volatile boolean

└── completedTaskCount: AtomicLong

Worker

├── thread: Thread

└── run(): void

RejectedPolicy

└── reject(Runnable task, ThreadPool pool): void
```



1. **执行流程**：

* 提交任务 → 检查线程池状态 → 尝试入队 → 队列满则执行拒绝策略

* 工作线程循环从队列取任务 → 执行任务 → 继续循环

#### 6.1.2 开发流程建议

建议按照以下步骤逐步实现：

**第一阶段：基础版本（只包含核心线程和简单任务执行）**



1. 定义 ThreadPool 类，包含基本字段

2. 实现 Worker 类，支持线程循环取任务

3. 实现 execute 方法，支持任务提交

4. 测试基本功能

**第二阶段：添加任务队列（支持任务缓冲）**



1. 添加 BlockingQueue 成员变量

2. 修改 Worker 类，使用阻塞队列的 take () 方法

3. 测试多任务提交和执行

**第三阶段：添加拒绝策略（处理队列满的情况）**



1. 定义 RejectedPolicy 接口

2. 实现几种内置拒绝策略

3. 在 execute 方法中添加拒绝策略调用

4. 测试队列满时的处理逻辑

**第四阶段：添加关闭功能（shutdown 和 shutdownNow）**



1. 添加 isShutdown 标志

2. 实现 shutdown 方法

3. 实现 shutdownNow 方法

4. 测试优雅关闭功能

**第五阶段：添加监控功能（统计信息）**



1. 添加任务计数功能

2. 添加线程活动监控

3. 添加队列状态监控

4. 实现各种 getter 方法

### 6.2 核心模块实现

现在开始实现简化版 ThreadPool 的核心模块。

#### 6.2.1 基础框架搭建

首先创建 ThreadPool 类的基本结构：



```
import java.util.HashSet;

import java.util.Set;

import java.util.concurrent.BlockingQueue;

import java.util.concurrent.LinkedBlockingQueue;

import java.util.concurrent.atomic.AtomicLong;

public class SimpleThreadPool {

&#x20;   private final int corePoolSize;

&#x20;   private final Set\<Worker> workers = new HashSet<>();

&#x20;   private final BlockingQueue\<Runnable> workQueue;

&#x20;   private final RejectedPolicy rejectedPolicy;

&#x20;   private volatile boolean isShutdown = false;

&#x20;   private final AtomicLong completedTaskCount = new AtomicLong();

&#x20;   public SimpleThreadPool(int corePoolSize) {

&#x20;       this(corePoolSize, new LinkedBlockingQueue<>(), new AbortPolicy());

&#x20;   }

&#x20;   public SimpleThreadPool(int corePoolSize, BlockingQueue\<Runnable> workQueue) {

&#x20;       this(corePoolSize, workQueue, new AbortPolicy());

&#x20;   }

&#x20;   public SimpleThreadPool(int corePoolSize,&#x20;

&#x20;                         BlockingQueue\<Runnable> workQueue,

&#x20;                         RejectedPolicy rejectedPolicy) {

&#x20;       this.corePoolSize = corePoolSize;

&#x20;       this.workQueue = workQueue;

&#x20;       this.rejectedPolicy = rejectedPolicy;

&#x20;      &#x20;

&#x20;       // 初始化核心线程

&#x20;       for (int i = 0; i < corePoolSize; i++) {

&#x20;           Worker worker = new Worker();

&#x20;           Thread thread = new Thread(worker, "SimpleThreadPool-Worker-" + i);

&#x20;           worker.setThread(thread);

&#x20;           workers.add(worker);

&#x20;           thread.start();

&#x20;       }

&#x20;   }

&#x20;   public void execute(Runnable task) {

&#x20;       if (task == null) {

&#x20;           throw new NullPointerException("Task cannot be null");

&#x20;       }

&#x20;      &#x20;

&#x20;       if (isShutdown) {

&#x20;           rejectedPolicy.reject(task, this);

&#x20;           return;

&#x20;       }

&#x20;      &#x20;

&#x20;       // 尝试将任务加入队列

&#x20;       if (!workQueue.offer(task)) {

&#x20;           // 队列已满，执行拒绝策略

&#x20;           rejectedPolicy.reject(task, this);

&#x20;       }

&#x20;   }

&#x20;   // 关闭方法

&#x20;   public void shutdown() {

&#x20;       isShutdown = true;

&#x20;       // 中断所有空闲线程

&#x20;       for (Worker worker : workers) {

&#x20;           worker.interruptIfIdle();

&#x20;       }

&#x20;   }

&#x20;   public void shutdownNow() {

&#x20;       isShutdown = true;

&#x20;       // 中断所有线程

&#x20;       for (Worker worker : workers) {

&#x20;           worker.interrupt();

&#x20;       }

&#x20;       // 清空队列

&#x20;       workQueue.clear();

&#x20;   }

&#x20;   // 监控方法

&#x20;   public int getCorePoolSize() {

&#x20;       return corePoolSize;

&#x20;   }

&#x20;   public int getActiveCount() {

&#x20;       return workers.size();

&#x20;   }

&#x20;   public long getCompletedTaskCount() {

&#x20;       return completedTaskCount.get();

&#x20;   }

&#x20;   public int getQueueSize() {

&#x20;       return workQueue.size();

&#x20;   }

&#x20;   public boolean isShutdown() {

&#x20;       return isShutdown;

&#x20;   }

&#x20;   // Worker内部类

&#x20;   private class Worker implements Runnable {

&#x20;       private Thread thread;

&#x20;       private volatile boolean isIdle = true;

&#x20;       public void setThread(Thread thread) {

&#x20;           this.thread = thread;

&#x20;       }

&#x20;       public void interrupt() {

&#x20;           thread.interrupt();

&#x20;       }

&#x20;       public void interruptIfIdle() {

&#x20;           if (isIdle) {

&#x20;               thread.interrupt();

&#x20;           }

&#x20;       }

&#x20;       @Override

&#x20;       public void run() {

&#x20;           try {

&#x20;               while (!Thread.currentThread().isInterrupted()) {

&#x20;                   isIdle = false;

&#x20;                   Runnable task = workQueue.take();

&#x20;                   isIdle = true;

&#x20;                  &#x20;

&#x20;                   if (task != null) {

&#x20;                       try {

&#x20;                           task.run();

&#x20;                           completedTaskCount.incrementAndGet();

&#x20;                       } catch (Exception e) {

&#x20;                           // 处理任务执行异常

&#x20;                       }

&#x20;                   }

&#x20;               }

&#x20;           } catch (InterruptedException e) {

&#x20;               // 线程被中断，正常退出

&#x20;           } finally {

&#x20;               synchronized (workers) {

&#x20;                   workers.remove(this);

&#x20;               }

&#x20;           }

&#x20;       }

&#x20;   }

&#x20;   // 拒绝策略接口

&#x20;   public interface RejectedPolicy {

&#x20;       void reject(Runnable task, SimpleThreadPool pool);

&#x20;   }

&#x20;   // 内置拒绝策略

&#x20;   public static class AbortPolicy implements RejectedPolicy {

&#x20;       @Override

&#x20;       public void reject(Runnable task, SimpleThreadPool pool) {

&#x20;           throw new RejectedExecutionException("Task " + task + " rejected from " + pool);

&#x20;       }

&#x20;   }

&#x20;   public static class CallerRunsPolicy implements RejectedPolicy {

&#x20;       @Override

&#x20;       public void reject(Runnable task, SimpleThreadPool pool) {

&#x20;           task.run();

&#x20;       }

&#x20;   }

&#x20;   public static class DiscardPolicy implements RejectedPolicy {

&#x20;       @Override

&#x20;       public void reject(Runnable task, SimpleThreadPool pool) {

&#x20;           // 静默丢弃

&#x20;       }

&#x20;   }

&#x20;   public static class DiscardOldestPolicy implements RejectedPolicy {

&#x20;       @Override

&#x20;       public void reject(Runnable task, SimpleThreadPool pool) {

&#x20;           Runnable oldTask = pool.workQueue.poll();

&#x20;           if (oldTask != null) {

&#x20;               pool.execute(task);

&#x20;           }

&#x20;       }

&#x20;   }

}
```

#### 6.2.2 核心机制实现

**Worker 线程的核心机制**：

Worker 线程的 run 方法实现了线程复用的核心逻辑：



```
@Override

public void run() {

&#x20;   try {

&#x20;       while (!Thread.currentThread().isInterrupted()) {

&#x20;           isIdle = false;

&#x20;           Runnable task = workQueue.take(); // 阻塞获取任务

&#x20;           isIdle = true;

&#x20;          &#x20;

&#x20;           if (task != null) {

&#x20;               task.run();

&#x20;               completedTaskCount.incrementAndGet();

&#x20;           }

&#x20;       }

&#x20;   } catch (InterruptedException e) {

&#x20;       // 线程被中断

&#x20;   } finally {

&#x20;       synchronized (workers) {

&#x20;           workers.remove(this);

&#x20;       }

&#x20;   }

}
```

关键机制说明：



1. **循环机制**：使用 while 循环持续获取任务，实现线程复用

2. **阻塞获取**：使用 BlockingQueue 的 take () 方法，无任务时阻塞

3. **中断响应**：通过检查 Thread.currentThread ().isInterrupted () 来响应中断

4. **状态维护**：isIdle 标志用于判断线程是否空闲，支持选择性中断

5. **资源清理**：finally 块中确保线程退出时从 workers 集合中移除

**任务提交的核心机制**：

execute 方法实现了任务的提交逻辑：



```
public void execute(Runnable task) {

&#x20;   if (task == null) {

&#x20;       throw new NullPointerException("Task cannot be null");

&#x20;   }

&#x20;  &#x20;

&#x20;   if (isShutdown) {

&#x20;       rejectedPolicy.reject(task, this);

&#x20;       return;

&#x20;   }

&#x20;  &#x20;

&#x20;   // 尝试将任务加入队列

&#x20;   if (!workQueue.offer(task)) {

&#x20;       // 队列已满，执行拒绝策略

&#x20;       rejectedPolicy.reject(task, this);

&#x20;   }

}
```

核心机制说明：



1. **空任务检查**：确保任务不为 null

2. **状态检查**：如果线程池已关闭，直接执行拒绝策略

3. **队列操作**：使用 offer 方法尝试入队，避免阻塞

4. **拒绝策略**：队列满时执行用户指定的拒绝策略

**线程池关闭机制**：

shutdown 方法实现了优雅关闭：



```
public void shutdown() {

&#x20;   isShutdown = true;

&#x20;   // 中断所有空闲线程

&#x20;   for (Worker worker : workers) {

&#x20;       worker.interruptIfIdle();

&#x20;   }

}
```

shutdownNow 方法实现了立即关闭：



```
public void shutdownNow() {

&#x20;   isShutdown = true;

&#x20;   // 中断所有线程

&#x20;   for (Worker worker : workers) {

&#x20;       worker.interrupt();

&#x20;   }

&#x20;   // 清空队列

&#x20;   workQueue.clear();

}
```

关闭机制说明：



1. **状态标志**：设置 isShutdown 为 true，不再接受新任务

2. **选择性中断**：shutdown 只中断空闲线程，shutdownNow 中断所有线程

3. **队列处理**：shutdownNow 会清空队列，丢弃所有未执行的任务

4. **线程退出**：Worker 线程在接收到中断后会退出循环，最终从 workers 集合中移除

### 6.3 测试与验证

编写测试用例验证简化版线程池的功能。

#### 6.3.1 功能测试用例

**基本功能测试**：



```
import org.junit.Test;

import java.util.concurrent.CountDownLatch;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

import static org.junit.Assert.assertFalse;

import static org.junit.Assert.assertTrue;

public class SimpleThreadPoolTest {

&#x20;   @Test

&#x20;   public void testBasicFunctionality() throws InterruptedException {

&#x20;       SimpleThreadPool pool = new SimpleThreadPool(2);

&#x20;      &#x20;

&#x20;       CountDownLatch latch = new CountDownLatch(5);

&#x20;      &#x20;

&#x20;       for (int i = 0; i < 5; i++) {

&#x20;           final int taskId = i;

&#x20;           pool.execute(() -> {

&#x20;               try {

&#x20;                   TimeUnit.MILLISECONDS.sleep(100);

&#x20;               } catch (InterruptedException e) {

&#x20;                   e.printStackTrace();

&#x20;               }

&#x20;               System.out.println("Task " + taskId + " executed by " + Thread.currentThread().getName());

&#x20;               latch.countDown();

&#x20;           });

&#x20;       }

&#x20;      &#x20;

&#x20;       latch.await();

&#x20;       pool.shutdown();

&#x20;      &#x20;

&#x20;       assertEquals(5, pool.getCompletedTaskCount());

&#x20;       assertTrue(pool.isShutdown());

&#x20;       assertEquals(0, pool.getQueueSize());

&#x20;   }

&#x20;   @Test

&#x20;   public void testRejectPolicy() {

&#x20;       // 使用容量为2的队列

&#x20;       SimpleThreadPool pool = new SimpleThreadPool(2, new LinkedBlockingQueue<>(2));

&#x20;      &#x20;

&#x20;       // 提交4个任务，前2个在核心线程执行，后2个在队列中

&#x20;       for (int i = 0; i < 4; i++) {

&#x20;           pool.execute(() -> {});

&#x20;       }

&#x20;      &#x20;

&#x20;       // 提交第5个任务，应该触发拒绝策略

&#x20;       try {

&#x20;           pool.execute(() -> {});

&#x20;       } catch (RejectedExecutionException e) {

&#x20;           System.out.println("Rejected task as expected: " + e.getMessage());

&#x20;       }

&#x20;      &#x20;

&#x20;       pool.shutdownNow();

&#x20;   }

&#x20;   @Test

&#x20;   public void testCallerRunsPolicy() {

&#x20;       SimpleThreadPool pool = new SimpleThreadPool(2, new LinkedBlockingQueue<>(2),&#x20;

&#x20;                                                   new SimpleThreadPool.CallerRunsPolicy());

&#x20;      &#x20;

&#x20;       // 记录调用线程

&#x20;       Thread mainThread = Thread.currentThread();

&#x20;      &#x20;

&#x20;       for (int i = 0; i < 5; i++) {

&#x20;           pool.execute(() -> {

&#x20;               if (Thread.currentThread() == mainThread) {

&#x20;                   System.out.println("Task executed by main thread (CallerRunsPolicy)");

&#x20;               } else {

&#x20;                   System.out.println("Task executed by worker thread");

&#x20;               }

&#x20;           });

&#x20;       }

&#x20;      &#x20;

&#x20;       pool.shutdown();

&#x20;   }

}
```

**性能测试**：



```
@Test

public void testPerformance() {

&#x20;   SimpleThreadPool pool = new SimpleThreadPool(10);

&#x20;   long start = System.currentTimeMillis();

&#x20;  &#x20;

&#x20;   // 提交10000个轻量级任务

&#x20;   for (int i = 0; i < 10000; i++) {

&#x20;       pool.execute(() -> {});

&#x20;   }

&#x20;  &#x20;

&#x20;   pool.shutdown();

&#x20;  &#x20;

&#x20;   // 等待所有任务完成

&#x20;   while (pool.getCompletedTaskCount() < 10000 && !pool.isTerminated()) {

&#x20;       try {

&#x20;           Thread.sleep(100);

&#x20;       } catch (InterruptedException e) {

&#x20;           e.printStackTrace();

&#x20;       }

&#x20;   }

&#x20;  &#x20;

&#x20;   long duration = System.currentTimeMillis() - start;

&#x20;   System.out.printf("Executed 10000 tasks in %d ms%n", duration);

&#x20;   System.out.printf("Average task time: %.2f ms%n", (double) duration / 10000);

}
```

#### 6.3.2 性能优化建议

基于测试结果，提出以下性能优化建议：



1. **队列选择优化**：

* 对于 CPU 密集型任务，使用有界队列（如 ArrayBlockingQueue），防止任务堆积

* 对于 IO 密集型任务，使用无界队列（如 LinkedBlockingQueue），充分利用线程

1. **线程数配置**：

* CPU 密集型：核心线程数 = CPU 核心数 + 1

* IO 密集型：核心线程数 = CPU 核心数 × 2

* 可通过 Runtime.getRuntime ().availableProcessors () 获取 CPU 核心数

1. **拒绝策略优化**：

* 高并发场景避免使用 AbortPolicy，可能导致大量异常

* 使用 CallerRunsPolicy 实现流量削峰

* 对非关键任务使用 DiscardPolicy

1. **监控与调优**：

* 定期监控队列长度，避免任务积压

* 监控活跃线程数，判断是否需要调整线程池大小

* 记录任务执行时间分布，识别性能瓶颈

1. **线程工厂优化**：

* 使用有意义的线程命名，便于问题定位

* 设置合理的线程优先级

* 考虑使用守护线程

1. **异常处理优化**：

* 在任务执行中添加 try-catch，避免线程意外终止

* 实现 afterExecute 钩子方法，记录异常信息

* 考虑使用线程池的 uncaughtExceptionHandler

通过以上优化，可以显著提升简化版线程池的性能和稳定性，使其能够应对更复杂的生产环境需求。

## 7. 总结与进阶学习建议

通过对 ThreadPoolExecutor 的深入分析和简化版实现，我们已经掌握了线程池的核心原理和实现方法。线程池作为 Java 并发编程的核心组件，其设计体现了许多优秀的编程思想和设计模式。

### 7.1 核心知识总结

**设计原理层面**：



* 线程池基于生产者 - 消费者模式，通过三级缓冲（核心线程、工作队列、临时线程）实现资源的弹性分配

* 工作队列的选择直接影响线程池的性能特性，需要根据任务类型和系统资源进行配置

* 线程复用机制通过 Worker 线程的循环取任务实现，避免了频繁创建和销毁线程的开销

* 拒绝策略提供了过载保护，确保系统在高负载下的稳定性

**实现细节层面**：



* 状态管理通过 ctl 变量的位运算设计实现，兼顾了空间效率和原子性保证

* 任务调度遵循 "核心线程 → 任务队列 → 临时线程 → 拒绝策略" 的四级处理机制

* Worker 类的双重角色设计（任务执行单元和同步控制单元）体现了设计的精巧性

* 钩子方法提供了良好的扩展性，支持在任务执行的不同阶段插入自定义逻辑

**高级功能层面**：



* 监控功能提供了丰富的运行时信息，是性能调优的重要依据

* 预启动线程功能可以减少首次任务的延迟，提高系统响应速度

* 动态调整参数功能增强了线程池的灵活性，能够适应负载的变化

* 线程工厂和拒绝策略的定制化支持满足了不同场景的特殊需求

### 7.2 进阶学习路径

**深入理解并发机制**：



1. 学习 AQS（AbstractQueuedSynchronizer）的原理和使用，这是理解 Worker 类锁机制的基础

2. 研究各种 BlockingQueue 的实现原理，特别是 ConcurrentLinkedQueue、LinkedBlockingQueue 等

3. 深入理解 CAS（Compare-And-Swap）操作和原子变量的使用

4. 学习线程中断机制和响应式编程模式

**性能优化实践**：



1. 研究不同类型任务（CPU 密集型、IO 密集型、混合型）的线程池配置策略

2. 学习如何使用监控工具（如 JConsole、VisualVM、Arthas）分析线程池性能

3. 实践动态线程池的实现，根据负载自动调整线程池大小

4. 研究如何避免线程池使用中的常见陷阱（如 ThreadLocal 污染、死锁等）

**扩展功能开发**：



1. 实现支持优先级的线程池，使用 PriorityBlockingQueue

2. 开发支持延迟和定时任务的线程池，类似 ScheduledThreadPoolExecutor

3. 实现支持任务分组和资源隔离的线程池

4. 开发线程池的监控和告警系统，集成 Prometheus、Grafana 等工具

**源码深入研究**：



1. 阅读 JDK 17 及以上版本的 ThreadPoolExecutor 源码，了解最新的优化

2. 研究 Doug Lea 的其他并发包实现（如 ConcurrentHashMap、CopyOnWriteArrayList 等）

3. 对比不同 JDK 版本中 ThreadPoolExecutor 的实现差异

4. 学习其他优秀的线程池实现（如 HikariCP 的线程池、Netty 的 EventLoop 等）

**实战项目经验**：



1. 在实际项目中使用和调优线程池，积累经验

2. 参与开源项目中线程池相关模块的开发和维护

3. 研究生产环境中线程池的性能问题和解决方案

4. 撰写技术博客分享经验，与社区交流学习

### 7.3 实践建议

**学习方法建议**：



1. **理论与实践结合**：不要只停留在理论学习，要动手实现和测试

2. **循序渐进**：从简化版开始，逐步添加功能，理解每个机制的作用

3. **代码阅读**：坚持阅读优秀的源码，学习设计思想和实现技巧

4. **问题驱动**：带着问题学习，通过解决实际问题加深理解

**生产环境使用建议**：



1. **合理配置参数**：根据任务特性和系统资源配置合适的线程数和队列大小

2. **监控告警**：建立完善的监控体系，及时发现和处理问题

3. **优雅关闭**：确保线程池能够优雅关闭，避免任务丢失

4. **性能调优**：根据实际运行数据持续优化线程池配置

**技术选型建议**：



1. 对于简单场景，可直接使用 Executors 提供的工厂方法

2. 对于复杂场景，建议直接使用 ThreadPoolExecutor 的构造函数，显式配置参数

3. 避免使用 FixedThreadPool 和 SingleThreadExecutor 的默认实现（无界队列）

4. 考虑使用其他优秀的线程池实现（如 HikariCP 的 FastList、Quasar 的 Fiber 等）

通过持续的学习和实践，相信你能够熟练掌握 ThreadPoolExecutor 的使用和优化，在高并发场景下构建出稳定、高效的系统。线程池技术的学习是一个长期的过程，需要不断地实践和总结，希望本指南能够成为你学习路上的有益参考。

**参考资料&#x20;**

\[1] Java并发编程实战深度解析线程池ThreadPoolExecutor的设计原理与性能优化策略-CSDN博客[ https://blog.csdn.net/kkjt0130/article/details/153129046](https://blog.csdn.net/kkjt0130/article/details/153129046)

\[2] 面试必问!Java 线程池 7 大核心机制与源码深挖 - 不夜天 - 博客园[ https://www.cnblogs.com/xuchangqing/articles/19128554](https://www.cnblogs.com/xuchangqing/articles/19128554)

\[3] 深度理解 Java 线程池:从原理到实践的全方位解析-腾讯云开发者社区-腾讯云[ https://cloud.tencent.com.cn/developer/article/2552729](https://cloud.tencent.com.cn/developer/article/2552729)

\[4] ThreadPoolExecutor设计思路解析与参数配置原理[ https://www.iesdouyin.com/share/video/7574646036535299379/?region=\&mid=7574646075735247667\&u\_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with\_sec\_did=1\&video\_share\_track\_ver=\&titleType=title\&share\_sign=e\_YPSJQAeUcul0IxArjrFV7aTC.IG56pf.Wc1RRY\_xU-\&share\_version=280700\&ts=1768313485\&from\_aid=1128\&from\_ssr=1\&share\_track\_info=%7B%22link\_description\_type%22%3A%22%22%7D](https://www.iesdouyin.com/share/video/7574646036535299379/?region=\&mid=7574646075735247667\&u_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with_sec_did=1\&video_share_track_ver=\&titleType=title\&share_sign=e_YPSJQAeUcul0IxArjrFV7aTC.IG56pf.Wc1RRY_xU-\&share_version=280700\&ts=1768313485\&from_aid=1128\&from_ssr=1\&share_track_info=%7B%22link_description_type%22%3A%22%22%7D)

\[5] 想要理解线程池其实很简单摘要:从一次"创建10万个线程导致服务器宕机"的乌龙事件出发，深度剖析线程池的核心原理与最佳实践 - 掘金[ https://juejin.cn/post/7563111162197278774](https://juejin.cn/post/7563111162197278774)

\[6] Java并发编程 - 线程池原理-CSDN博客[ https://blog.csdn.net/weixin\_46619605/article/details/150552694](https://blog.csdn.net/weixin_46619605/article/details/150552694)

\[7] Java线程池详解与实战指南线程池是Java并发编程中的核心组件，它通过复用线程资源、控制并发数量、管理任务队列等机制， - 掘金[ https://juejin.cn/post/7551151758400897058](https://juejin.cn/post/7551151758400897058)

\[8] Java面试题2:Java线程池原理-CSDN博客[ https://blog.csdn.net/m0\_72765822/article/details/154703539](https://blog.csdn.net/m0_72765822/article/details/154703539)

\[9] 线程池ThreadPoolExecutor源码分析(JDK 17)本文深度剖析JDK 17线程池(ThreadPoolE - 掘金[ https://juejin.cn/post/7537644857382535177](https://juejin.cn/post/7537644857382535177)

\[10] java 线程池 ThreadPoolExecutor - CSDN文库[ https://wenku.csdn.net/answer/477z3irrs2](https://wenku.csdn.net/answer/477z3irrs2)

\[11] Java 线程池[ https://github.com/fancyfei/techworld/blob/master/java/thread/thread\_pool.md](https://github.com/fancyfei/techworld/blob/master/java/thread/thread_pool.md)

\[12] 深入剖析Java线程池:原理、设计与最佳实践\_让世界更美好的技术博客\_51CTO博客[ https://blog.51cto.com/u\_15912723/14124268](https://blog.51cto.com/u_15912723/14124268)

\[13] Java线程池详解:高效并发编程的核心利器Java线程池详解:高效并发编程的核心利器 一、什么是线程池 1. 线程池的定 - 掘金[ https://juejin.cn/post/7517469596761358374](https://juejin.cn/post/7517469596761358374)

\[14] Java线程池深度解析:参数、策略、底层原理与最佳实践-CSDN博客[ https://blog.csdn.net/ZuanShi1111/article/details/151324104](https://blog.csdn.net/ZuanShi1111/article/details/151324104)

\[15] 一文详细解析ThreadPoolExector线程池\_threadpoolexecutor 队列-CSDN博客[ https://blog.csdn.net/m0\_53269463/article/details/150301338](https://blog.csdn.net/m0_53269463/article/details/150301338)

\[16] クラスThreadPoolExecutor[ https://docs.oracle.com/javase/jp/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html](https://docs.oracle.com/javase/jp/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)

\[17] 并发--线程池(3)中的任务队列(workQueue)\_线程池任务队列-CSDN博客[ https://blog.csdn.net/m0\_57921272/article/details/149404899](https://blog.csdn.net/m0_57921272/article/details/149404899)

\[18] Java线程池常见阻塞队列对比与应用场景解析[ https://www.iesdouyin.com/share/video/7585466847382785299/?region=\&mid=7585466920896351026\&u\_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with\_sec\_did=1\&video\_share\_track\_ver=\&titleType=title\&share\_sign=uM27rMSXCcYNm4UKpYulWG2aG5J4w.kTSiSfxh8.euM-\&share\_version=280700\&ts=1768313507\&from\_aid=1128\&from\_ssr=1\&share\_track\_info=%7B%22link\_description\_type%22%3A%22%22%7D](https://www.iesdouyin.com/share/video/7585466847382785299/?region=\&mid=7585466920896351026\&u_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with_sec_did=1\&video_share_track_ver=\&titleType=title\&share_sign=uM27rMSXCcYNm4UKpYulWG2aG5J4w.kTSiSfxh8.euM-\&share_version=280700\&ts=1768313507\&from_aid=1128\&from_ssr=1\&share_track_info=%7B%22link_description_type%22%3A%22%22%7D)

\[19] 线程池 - deyang - 博客园[ https://www.cnblogs.com/hellodeyang/p/19130679](https://www.cnblogs.com/hellodeyang/p/19130679)

\[20] 线程池 ThreadPoolExecutor 和 Redisson 延时队列\_柳随风的技术博客\_51CTO博客[ https://blog.51cto.com/u\_14276/13528215](https://blog.51cto.com/u_14276/13528215)

\[21] 一句话说透Java里面的线程池的工作队列一句话总结: 线程池的工作队列就像快递站的候客区——任务太多时先排队，队列类型不 - 掘金[ https://juejin.cn/post/7475349928128806950](https://juejin.cn/post/7475349928128806950)

\[22] 线程池中线程复⽤原理\_线程池线程复用原理-CSDN博客[ https://blog.csdn.net/qq\_35426036/article/details/147018775](https://blog.csdn.net/qq_35426036/article/details/147018775)

\[23] 面试必问!Java 线程池 7 大核心机制与源码深挖 - 不夜天 - 博客园[ https://www.cnblogs.com/xuchangqing/articles/19128554](https://www.cnblogs.com/xuchangqing/articles/19128554)

\[24] ThreadPoolExecutor执行过程详解-CSDN博客[ https://blog.csdn.net/chenxuefeng\_accp/article/details/148851883](https://blog.csdn.net/chenxuefeng_accp/article/details/148851883)

\[25] Java并发编程:从源码分析ThreadPoolExecutor 的三大核心机制Java并发编程:从源码分析Thread - 掘金[ https://juejin.cn/post/7555690975864930319](https://juejin.cn/post/7555690975864930319)

\[26] 线程池的线程复用原理-CSDN博客[ https://blog.csdn.net/xiewenfeng520/article/details/107013665](https://blog.csdn.net/xiewenfeng520/article/details/107013665)

\[27] 线程池中线程是如何保活和回收的-51CTO.COM[ https://www.51cto.com/article/799082.html](https://www.51cto.com/article/799082.html)

\[28] 面试官:谈谈你对线程池拒绝策略的理解?\_51CTO博客\_线程池拒绝策略有哪些[ https://blog.51cto.com/vipstone/14086063](https://blog.51cto.com/vipstone/14086063)

\[29] 说一下Java里面线程池的拒绝策略\_java自定义线程池拒绝策略-CSDN博客[ https://blog.csdn.net/weixin\_46028606/article/details/148620169](https://blog.csdn.net/weixin_46028606/article/details/148620169)

\[30] Java线程池ThreadPoolExecutor四种拒绝策略原理分析与应用-开发者社区-阿里云[ https://developer.aliyun.com/article/1471646](https://developer.aliyun.com/article/1471646)

\[31] 线程池拒绝策略的实战陷阱与正确选型[ https://www.iesdouyin.com/share/video/7588824757181746447/?region=\&mid=7588824868620077850\&u\_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with\_sec\_did=1\&video\_share\_track\_ver=\&titleType=title\&share\_sign=cIuFV7qK5yBa\_0htyDl4wC1PZ\_sdpaxSoL4gaTou2.0-\&share\_version=280700\&ts=1768313521\&from\_aid=1128\&from\_ssr=1\&share\_track\_info=%7B%22link\_description\_type%22%3A%22%22%7D](https://www.iesdouyin.com/share/video/7588824757181746447/?region=\&mid=7588824868620077850\&u_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with_sec_did=1\&video_share_track_ver=\&titleType=title\&share_sign=cIuFV7qK5yBa_0htyDl4wC1PZ_sdpaxSoL4gaTou2.0-\&share_version=280700\&ts=1768313521\&from_aid=1128\&from_ssr=1\&share_track_info=%7B%22link_description_type%22%3A%22%22%7D)

\[32] 线程池的拒绝策略有哪些?\_mb662090d623fca的技术博客\_51CTO博客[ https://blog.51cto.com/u\_16717381/13849196](https://blog.51cto.com/u_16717381/13849196)

\[33] 面试官狂喜!我用这 5 分钟讲清了 ThreadPoolExecutor 饱和策略，逆袭上岸社招面试被问到 Thread - 掘金[ https://juejin.cn/post/7493415712120586292](https://juejin.cn/post/7493415712120586292)

\[34] Class ThreadPoolExecutor[ https://tomcat.apache.org/tomcat-8.5-doc/api/org/apache/tomcat/util/threads/ThreadPoolExecutor.html](https://tomcat.apache.org/tomcat-8.5-doc/api/org/apache/tomcat/util/threads/ThreadPoolExecutor.html)

\[35] 线程池(以Java的ThreadPoolExecutor为例)在其生命周期中有5种状态\_线程池初始化时是什么状态-CSDN博客[ https://blog.csdn.net/m0\_61600773/article/details/146064805](https://blog.csdn.net/m0_61600773/article/details/146064805)

\[36] 深入解析线程池状态:从原理到实践，掌控并发核心-CSDN博客[ https://blog.csdn.net/2401\_87398486/article/details/152052105](https://blog.csdn.net/2401_87398486/article/details/152052105)

\[37] 线程池状态和关闭操作 - Hekk丶 - 博客园[ https://www.cnblogs.com/hekk/p/19209506](https://www.cnblogs.com/hekk/p/19209506)

\[38] 线程池七大核心问题解析与面试应对[ https://www.iesdouyin.com/share/video/7571488244529892662/?region=\&mid=7571488355628747529\&u\_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with\_sec\_did=1\&video\_share\_track\_ver=\&titleType=title\&share\_sign=GVVQBlUYIizcnKMQtkjCvposmOsDdit3gbio7k43TGE-\&share\_version=280700\&ts=1768313521\&from\_aid=1128\&from\_ssr=1\&share\_track\_info=%7B%22link\_description\_type%22%3A%22%22%7D](https://www.iesdouyin.com/share/video/7571488244529892662/?region=\&mid=7571488355628747529\&u_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with_sec_did=1\&video_share_track_ver=\&titleType=title\&share_sign=GVVQBlUYIizcnKMQtkjCvposmOsDdit3gbio7k43TGE-\&share_version=280700\&ts=1768313521\&from_aid=1128\&from_ssr=1\&share_track_info=%7B%22link_description_type%22%3A%22%22%7D)

\[39] 并发编程之:深入解析线程池-鸿蒙开发者社区-51CTO.COM[ https://ost.51cto.com/posts/18070](https://ost.51cto.com/posts/18070)

\[40] ThreadPoolExecutor简单介绍ThreadPoolExecutor作为并发编程核心之一，值得大家深入了解其 - 掘金[ https://juejin.cn/post/7474625090647359488](https://juejin.cn/post/7474625090647359488)

\[41] 线程池的状态-CSDN博客[ https://blog.csdn.net/2301\_80008738/article/details/149544433](https://blog.csdn.net/2301_80008738/article/details/149544433)

\[42] Java线程池状态终极指南:位运算设计&& 面试考点&& 全流程图解-CSDN博客[ https://blog.csdn.net/2301\_78447438/article/details/150469911](https://blog.csdn.net/2301_78447438/article/details/150469911)

\[43] JUC学习笔记-----线程池-CSDN博客[ https://blog.csdn.net/2301\_80860862/article/details/150580128](https://blog.csdn.net/2301_80860862/article/details/150580128)

\[44] ThreadPoolExecutor中CTL机制的核心设计与线程池管理[ https://www.iesdouyin.com/share/video/7458631218561551653/?region=\&mid=7458632099621260042\&u\_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with\_sec\_did=1\&video\_share\_track\_ver=\&titleType=title\&share\_sign=LlR6qhOxZSHzwDzqmSn8Z1gKbnh3hCbLPdCaBLU9evc-\&share\_version=280700\&ts=1768313536\&from\_aid=1128\&from\_ssr=1\&share\_track\_info=%7B%22link\_description\_type%22%3A%22%22%7D](https://www.iesdouyin.com/share/video/7458631218561551653/?region=\&mid=7458632099621260042\&u_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with_sec_did=1\&video_share_track_ver=\&titleType=title\&share_sign=LlR6qhOxZSHzwDzqmSn8Z1gKbnh3hCbLPdCaBLU9evc-\&share_version=280700\&ts=1768313536\&from_aid=1128\&from_ssr=1\&share_track_info=%7B%22link_description_type%22%3A%22%22%7D)

\[45] 【Java源码阅读系列27】深度解读Java ThreadPoolExecutor 源码\_threadpoolexecutor ctl参数-CSDN博客[ https://blog.csdn.net/gaosw0521/article/details/148941663](https://blog.csdn.net/gaosw0521/article/details/148941663)

\[46] ThreadPoolExecutor详解与应用实践hreadPoolExecutor是Java并发编程中最核心的线程池实 - 掘金[ https://juejin.cn/post/7553985063395393575](https://juejin.cn/post/7553985063395393575)

\[47] ThreadPoolExecutor类(线程池)的介绍和使用-CSDN博客[ https://blog.csdn.net/weixin\_44891364/article/details/146287109](https://blog.csdn.net/weixin_44891364/article/details/146287109)

\[48] ThreadPoolExecutor\_threadpoolexecutor delayqueue-CSDN博客[ https://blog.csdn.net/weixin\_43907800/article/details/104719627](https://blog.csdn.net/weixin_43907800/article/details/104719627)

\[49] 从源码到实战:线程池处理任务的完整流程解析-CSDN博客[ https://blog.csdn.net/qq\_59219765/article/details/155464321](https://blog.csdn.net/qq_59219765/article/details/155464321)

\[50] 从源码视角深入解读ThreadPoolExecutor的执行流程\_threadpoolexecutor的工作流程-CSDN博客[ https://blog.csdn.net/feiying101/article/details/138258480](https://blog.csdn.net/feiying101/article/details/138258480)

\[51] Java并发编程:从源码分析ThreadPoolExecutor 的三大核心机制Java并发编程:从源码分析Thread - 掘金[ https://juejin.cn/post/7555690975864930319](https://juejin.cn/post/7555690975864930319)

\[52] 解析Java线程池execute方法执行流程及核心逻辑[ https://www.iesdouyin.com/share/video/7567352683858758931/?region=\&mid=7567352779040230180\&u\_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with\_sec\_did=1\&video\_share\_track\_ver=\&titleType=title\&share\_sign=g2bpupnBrgvSmE8uqNzYJuqaHfnCcS7\_WqPagZUn87w-\&share\_version=280700\&ts=1768313537\&from\_aid=1128\&from\_ssr=1\&share\_track\_info=%7B%22link\_description\_type%22%3A%22%22%7D](https://www.iesdouyin.com/share/video/7567352683858758931/?region=\&mid=7567352779040230180\&u_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with_sec_did=1\&video_share_track_ver=\&titleType=title\&share_sign=g2bpupnBrgvSmE8uqNzYJuqaHfnCcS7_WqPagZUn87w-\&share_version=280700\&ts=1768313537\&from_aid=1128\&from_ssr=1\&share_track_info=%7B%22link_description_type%22%3A%22%22%7D)

\[53] 线程池源码剖析:ThreadPoolExecutor任务调度机制全解析\_java threadpoolexecutor如何执行调度队列任务-CSDN博客[ https://blog.csdn.net/m0\_74225871/article/details/148047747](https://blog.csdn.net/m0_74225871/article/details/148047747)

\[54] 🏗️ 线程池深度解析：ThreadPoolExecutor底层实现与CompletableFuture异步编程实战[ https://juejin.cn/post/7546745197703823398](https://juejin.cn/post/7546745197703823398)

\[55] 深入 JVM:线程池源码剖析与性能调优全攻略 - Java炼金术 - SegmentFault 思否[ https://blog.segmentfault.com/a/1190000046455251?sort=newest](https://blog.segmentfault.com/a/1190000046455251?sort=newest)

\[56] 当线程池ThreadPool需要线程的时候是如何创建线程的\_java线程池何时创建新线程-CSDN博客[ https://blog.csdn.net/weixin\_49622776/article/details/148947099](https://blog.csdn.net/weixin_49622776/article/details/148947099)

\[57] 【线程池的执行流程】-CSDN博客[ https://blog.csdn.net/qq\_37205597/article/details/150353895](https://blog.csdn.net/qq_37205597/article/details/150353895)

\[58] 线程池执行器ThreadPoolExecutor源码完整解读\_用提交者所在的线程来执行任务-CSDN博客[ https://blog.csdn.net/xiaowu\_first/article/details/105211853](https://blog.csdn.net/xiaowu_first/article/details/105211853)

\[59] 深入 JVM:线程池源码剖析与性能调优全攻略深入JVM线程池源码，掌握科学调优与性能优化技巧。从底层原理到实战案例，帮你 - 掘金[ https://juejin.cn/post/7493786100445888524](https://juejin.cn/post/7493786100445888524)

\[60] 阿里p8架构师谈:彻底弄懂 Java 线程池原理\_java线程池实现原理 阿里-CSDN博客[ https://blog.csdn.net/GD\_qingfeng/article/details/119059142](https://blog.csdn.net/GD_qingfeng/article/details/119059142)

\[61] 第04节:Worker线程核心执行流程解析 | 冰河技术[ https://binghe001.github.io/md/project/threadpool/jdk/2025-08-30-chapter04.html](https://binghe001.github.io/md/project/threadpool/jdk/2025-08-30-chapter04.html)

\[62] 从源码视角深入解读ThreadPoolExecutor的执行流程\_threadpoolexecutor的工作流程-CSDN博客[ https://blog.csdn.net/feiying101/article/details/138258480](https://blog.csdn.net/feiying101/article/details/138258480)

\[63] 线程池ThreadPoolExecutor源码分析(JDK 17)本文深度剖析JDK 17线程池(ThreadPoolE - 掘金[ https://juejin.cn/post/7537644857382535177](https://juejin.cn/post/7537644857382535177)

\[64] 线程池ThreadPoolExecutor中线程的创建与销毁\_线程池销毁-CSDN博客[ https://blog.csdn.net/weixin\_37873870/article/details/122821100](https://blog.csdn.net/weixin_37873870/article/details/122821100)

\[65] Java线程池添加工作线程流程解析[ https://www.iesdouyin.com/share/video/7582884559235140916/?region=\&mid=7582884699413203766\&u\_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with\_sec\_did=1\&video\_share\_track\_ver=\&titleType=title\&share\_sign=MYNRGhPm16SVUcgOu5GSPHBYo7i9tpH\_uGBrxGPiY90-\&share\_version=280700\&ts=1768313552\&from\_aid=1128\&from\_ssr=1\&share\_track\_info=%7B%22link\_description\_type%22%3A%22%22%7D](https://www.iesdouyin.com/share/video/7582884559235140916/?region=\&mid=7582884699413203766\&u_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with_sec_did=1\&video_share_track_ver=\&titleType=title\&share_sign=MYNRGhPm16SVUcgOu5GSPHBYo7i9tpH_uGBrxGPiY90-\&share_version=280700\&ts=1768313552\&from_aid=1128\&from_ssr=1\&share_track_info=%7B%22link_description_type%22%3A%22%22%7D)

\[66] Java并发编程:从源码分析ThreadPoolExecutor 的三大核心机制Java并发编程:从源码分析Thread - 掘金[ https://juejin.cn/post/7555690975864930319](https://juejin.cn/post/7555690975864930319)

\[67] 并发编程-ThreadPoolExecutor 源码解析\_threadpoolexecutor addworker-CSDN博客[ https://blog.csdn.net/qq\_30631063/article/details/103238717](https://blog.csdn.net/qq_30631063/article/details/103238717)

\[68] ThreadPoolExecutor线程池底层原理分析 - jock\_javaEE - 博客园[ https://www.cnblogs.com/jock766/p/18251221](https://www.cnblogs.com/jock766/p/18251221)

\[69] 在Java中如何监控线程池运行状态\_线程池运维思路说明-java教程-PHP中文网[ https://m.php.cn/faq/1913721.html](https://m.php.cn/faq/1913721.html)

\[70] 如何在Java中监控线程池状态-java教程-PHP中文网[ https://m.php.cn/faq/1592387.html](https://m.php.cn/faq/1592387.html)

\[71] 监控线程池的状态\_线程池监控-CSDN博客[ https://blog.csdn.net/qq\_41893505/article/details/146219787](https://blog.csdn.net/qq_41893505/article/details/146219787)

\[72] 基于普罗米修斯与dynamic-tp框架的动态线程池调参[ https://www.iesdouyin.com/share/video/7491706658255572250/?region=\&mid=7491707021297732392\&u\_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with\_sec\_did=1\&video\_share\_track\_ver=\&titleType=title\&share\_sign=wSeShn.ne8\_lR7sHeBXHcJ9EeaTMB70SgKeM9zpBHxI-\&share\_version=280700\&ts=1768313577\&from\_aid=1128\&from\_ssr=1\&share\_track\_info=%7B%22link\_description\_type%22%3A%22%22%7D](https://www.iesdouyin.com/share/video/7491706658255572250/?region=\&mid=7491707021297732392\&u_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with_sec_did=1\&video_share_track_ver=\&titleType=title\&share_sign=wSeShn.ne8_lR7sHeBXHcJ9EeaTMB70SgKeM9zpBHxI-\&share_version=280700\&ts=1768313577\&from_aid=1128\&from_ssr=1\&share_track_info=%7B%22link_description_type%22%3A%22%22%7D)

\[73] Java线程池教程第13章:监控与调试 - 线程池性能监控和问题诊断指南[ https://gaga.plus/app/thread-pool/chapter-13-monitoring/](https://gaga.plus/app/thread-pool/chapter-13-monitoring/)

\[74] Java日志中的线程池监控方法 - 问答 - 亿速云[ https://www.yisu.com/ask/70578991.html](https://www.yisu.com/ask/70578991.html)

\[75] java 程序运行期间，动态地查看线程池的使用情况 - gongchengship - 博客园[ https://www.cnblogs.com/gongchengship/p/18455486](https://www.cnblogs.com/gongchengship/p/18455486)

\[76] Java线程池 - 深入解析ThreadPoolExecutor的底层原理(源码全面讲解一篇就够)-CSDN博客[ https://blog.csdn.net/GDUT\_xin/article/details/147341723](https://blog.csdn.net/GDUT_xin/article/details/147341723)

\[77] ThreadPoolExecutor简介& 源码解析\_threadpoolexecutor源码解析-CSDN博客[ https://blog.csdn.net/jiaoshi5167/article/details/129250662](https://blog.csdn.net/jiaoshi5167/article/details/129250662)

\[78] 带日志和计时等功能的线程池\_beforeexecute 抛出异常runtimeexception-CSDN博客[ https://blog.csdn.net/danielzhou888/article/details/84074366](https://blog.csdn.net/danielzhou888/article/details/84074366)

\[79] Java线程池扩展方法实现监控与异常处理[ https://www.iesdouyin.com/share/video/7298688918495530259/?region=\&mid=7298689019448167219\&u\_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with\_sec\_did=1\&video\_share\_track\_ver=\&titleType=title\&share\_sign=0Xq4zuJWa5\_CwxamrA1cgk9\_ux82qautKj37GdGyfq4-\&share\_version=280700\&ts=1768313577\&from\_aid=1128\&from\_ssr=1\&share\_track\_info=%7B%22link\_description\_type%22%3A%22%22%7D](https://www.iesdouyin.com/share/video/7298688918495530259/?region=\&mid=7298689019448167219\&u_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with_sec_did=1\&video_share_track_ver=\&titleType=title\&share_sign=0Xq4zuJWa5_CwxamrA1cgk9_ux82qautKj37GdGyfq4-\&share_version=280700\&ts=1768313577\&from_aid=1128\&from_ssr=1\&share_track_info=%7B%22link_description_type%22%3A%22%22%7D)

\[80] Extending ThreadPoolExecutor[ https://flylib.com/books/en/2.558.1/extending\_threadpoolexecutor.html](https://flylib.com/books/en/2.558.1/extending_threadpoolexecutor.html)

\[81] 以利用ThreadPoolExecutor的钩子方法进行 - CSDN文库[ https://wenku.csdn.net/answer/20jq3zwmyh](https://wenku.csdn.net/answer/20jq3zwmyh)

\[82] ThreadPoolExecutor之Hook methods! - 一骑红尘妃子笑! - 博客园[ https://www.cnblogs.com/iuyy/p/13622154.html](https://www.cnblogs.com/iuyy/p/13622154.html)

\[83] 线程池 的参数 prestartAllCoreThreads ,allowcoreThreadTimeOut说明\_潇凝子潇的技术博客\_51CTO博客[ https://blog.51cto.com/u\_4981212/14194843](https://blog.51cto.com/u_4981212/14194843)

\[84] 聊聊线程池的预热\_线程池预热-CSDN博客[ https://blog.csdn.net/hello\_ejb3/article/details/133999032](https://blog.csdn.net/hello_ejb3/article/details/133999032)

\[85] ThreadPoolExecutor源码解析(jdk1.8)\_threadpoolexecutor pool size包含已完成的-CSDN博客[ https://blog.csdn.net/m0\_37148920/article/details/98891934](https://blog.csdn.net/m0_37148920/article/details/98891934)

\[86] 线程池预热的实现方法及核心线程创建策略[ https://www.iesdouyin.com/share/video/7480541875045240104/?region=\&mid=7480541856948390675\&u\_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with\_sec\_did=1\&video\_share\_track\_ver=\&titleType=title\&share\_sign=mGbfBemgKdB\_u91Q.xjmQITP8ATs72pSLMR7\_8IaR.4-\&share\_version=280700\&ts=1768313582\&from\_aid=1128\&from\_ssr=1\&share\_track\_info=%7B%22link\_description\_type%22%3A%22%22%7D](https://www.iesdouyin.com/share/video/7480541875045240104/?region=\&mid=7480541856948390675\&u_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with_sec_did=1\&video_share_track_ver=\&titleType=title\&share_sign=mGbfBemgKdB_u91Q.xjmQITP8ATs72pSLMR7_8IaR.4-\&share_version=280700\&ts=1768313582\&from_aid=1128\&from_ssr=1\&share_track_info=%7B%22link_description_type%22%3A%22%22%7D)

\[87] Java线程池怎么做预热?从硬编码到pool.prestartCoreThreadJava线程池怎么做预热?从简单到复杂 - 掘金[ https://juejin.cn/post/7481098437775654938](https://juejin.cn/post/7481098437775654938)

\[88] Java线程池 - 深入解析ThreadPoolExecutor的底层原理(源码全面讲解一篇就够)-CSDN博客[ https://blog.csdn.net/GDUT\_xin/article/details/147341723](https://blog.csdn.net/GDUT_xin/article/details/147341723)

\[89] ThreadPoolExecutor 没有进程了 再关闭 shutdown\_mob6454cc6caa80的技术博客\_51CTO博客[ https://blog.51cto.com/u\_16099244/13994117](https://blog.51cto.com/u_16099244/13994117)

\[90] 线程池参数的动态化原理及集成nacos实践-腾讯云开发者社区-腾讯云[ https://cloud.tencent.com.cn/developer/article/2486458](https://cloud.tencent.com.cn/developer/article/2486458)

\[91] Class ThreadPoolExecutor[ https://tomcat.apache.org/tomcat-8.5-doc/api/org/apache/tomcat/util/threads/ThreadPoolExecutor.html](https://tomcat.apache.org/tomcat-8.5-doc/api/org/apache/tomcat/util/threads/ThreadPoolExecutor.html)

\[92] 线程池中 “允许核心超时“\_允许核心线程超时-CSDN博客[ https://blog.csdn.net/qq\_37205597/article/details/150354095](https://blog.csdn.net/qq_37205597/article/details/150354095)

\[93] 线程池面试核心问题解析与应对策略[ https://www.iesdouyin.com/share/video/7498694752654462245/?region=\&mid=7498694691730410294\&u\_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with\_sec\_did=1\&video\_share\_track\_ver=\&titleType=title\&share\_sign=VKmAuViSUkXHO2J6kT3V5SwdQa00LIAQQXCXi5AXpdA-\&share\_version=280700\&ts=1768313582\&from\_aid=1128\&from\_ssr=1\&share\_track\_info=%7B%22link\_description\_type%22%3A%22%22%7D](https://www.iesdouyin.com/share/video/7498694752654462245/?region=\&mid=7498694691730410294\&u_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with_sec_did=1\&video_share_track_ver=\&titleType=title\&share_sign=VKmAuViSUkXHO2J6kT3V5SwdQa00LIAQQXCXi5AXpdA-\&share_version=280700\&ts=1768313582\&from_aid=1128\&from_ssr=1\&share_track_info=%7B%22link_description_type%22%3A%22%22%7D)

\[94] ThreadPoolExecutor详解与应用实践hreadPoolExecutor是Java并发编程中最核心的线程池实 - 掘金[ https://juejin.cn/post/7553985063395393575](https://juejin.cn/post/7553985063395393575)

\[95] 线程池可以运行时修改它的参数吗\_java 线程池参数可以动态调节吗?-CSDN博客[ https://blog.csdn.net/u011305680/article/details/151589105](https://blog.csdn.net/u011305680/article/details/151589105)

\[96] ThreadPoolExecutor线程池详解\_threadpoolexecutor getactivecount-CSDN博客[ https://blog.csdn.net/IT\_Most/article/details/108719730](https://blog.csdn.net/IT_Most/article/details/108719730)

\[97] ThreadPoolExecutor (Java Platform SE 8 )[ http://docs.oracle.com/javase/8/docs/api/?java/util/concurrent/ThreadPoolExecutor.html](http://docs.oracle.com/javase/8/docs/api/?java/util/concurrent/ThreadPoolExecutor.html)

\[98] Java线程池七个参数详解:核心线程数、最大线程数、空闲线程存活时间、时间单位、工作队列、线程工厂、拒绝策略\_java 线程池 核心线程数 最大线程数 队列-CSDN博客[ https://blog.csdn.net/2401\_84004012/article/details/137851876](https://blog.csdn.net/2401_84004012/article/details/137851876)

\[99] Java线程池ThreadPoolExecutor参数解析\_java threadpoolexecutor 参数解析-CSDN博客[ https://blog.csdn.net/a491857321/article/details/78969164](https://blog.csdn.net/a491857321/article/details/78969164)

\[100] Java线程池核心原理与实践配置解析[ https://www.iesdouyin.com/share/video/7522059516437007674/?region=\&mid=6577225329566616324\&u\_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with\_sec\_did=1\&video\_share\_track\_ver=\&titleType=title\&share\_sign=zfH\_svEl4lmaK4tTN.KJjawegEsbzxDJCFaEP3AT37c-\&share\_version=280700\&ts=1768313597\&from\_aid=1128\&from\_ssr=1\&share\_track\_info=%7B%22link\_description\_type%22%3A%22%22%7D](https://www.iesdouyin.com/share/video/7522059516437007674/?region=\&mid=6577225329566616324\&u_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with_sec_did=1\&video_share_track_ver=\&titleType=title\&share_sign=zfH_svEl4lmaK4tTN.KJjawegEsbzxDJCFaEP3AT37c-\&share_version=280700\&ts=1768313597\&from_aid=1128\&from_ssr=1\&share_track_info=%7B%22link_description_type%22%3A%22%22%7D)

\[101] 线程池(ThreadPoolExecutor)具体参数\_threadpoolexecutor factory参数-CSDN博客[ https://blog.csdn.net/More\_speed/article/details/116157209](https://blog.csdn.net/More_speed/article/details/116157209)

\[102] Class Executors[ http://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Executors.html](http://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Executors.html)

\[103] java.util.concurrent

&#x20;クラス ThreadPoolExecutor[ https://docs.oracle.com/javase/jp/1.5.0/api/java/util/concurrent/ThreadPoolExecutor.html](https://docs.oracle.com/javase/jp/1.5.0/api/java/util/concurrent/ThreadPoolExecutor.html)

\[104] blog/Java/ThreadPoolExecutor的行为模式.md at master · prufeng/blog · GitHub[ https://github.com/prufeng/blog/blob/master/Java/ThreadPoolExecutor%E7%9A%84%E8%A1%8C%E4%B8%BA%E6%A8%A1%E5%BC%8F.md](https://github.com/prufeng/blog/blob/master/Java/ThreadPoolExecutor%E7%9A%84%E8%A1%8C%E4%B8%BA%E6%A8%A1%E5%BC%8F.md)

\[105] Java线程池使用场景与ThreadPoolExecutor正确创建方式解析[ https://www.iesdouyin.com/share/video/7537215190165769482/?region=\&mid=7537215336664337191\&u\_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with\_sec\_did=1\&video\_share\_track\_ver=\&titleType=title\&share\_sign=cAxqrhu4Ct9M4hO.zWecAcpCxIF0dg16k8NRxztBcPk-\&share\_version=280700\&ts=1768313597\&from\_aid=1128\&from\_ssr=1\&share\_track\_info=%7B%22link\_description\_type%22%3A%22%22%7D](https://www.iesdouyin.com/share/video/7537215190165769482/?region=\&mid=7537215336664337191\&u_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with_sec_did=1\&video_share_track_ver=\&titleType=title\&share_sign=cAxqrhu4Ct9M4hO.zWecAcpCxIF0dg16k8NRxztBcPk-\&share_version=280700\&ts=1768313597\&from_aid=1128\&from_ssr=1\&share_track_info=%7B%22link_description_type%22%3A%22%22%7D)

\[106] GitHub\_Trending/jd/jdk线程池实现:高效管理并发任务的线程资源-CSDN博客[ https://blog.csdn.net/gitblog\_01122/article/details/152405823](https://blog.csdn.net/gitblog_01122/article/details/152405823)

\[107] 🏗️ 线程池深度解析：ThreadPoolExecutor底层实现与CompletableFuture异步编程实战[ https://juejin.cn/post/7546745197703823398](https://juejin.cn/post/7546745197703823398)

\[108] Java多线程(四)——ThreadPoolExecutor源码解析\_java threadpoolexecutor demo-CSDN博客[ https://blog.csdn.net/weixin\_43093006/article/details/128760576](https://blog.csdn.net/weixin_43093006/article/details/128760576)

\[109] 2 w字长文带你深入理解线程池-鸿蒙开发者社区-51CTO.COM[ https://ost.51cto.com/posts/19814](https://ost.51cto.com/posts/19814)

\[110] Java线程池源码解析-CSDN博客[ https://blog.csdn.net/weixin\_46016309/article/details/151222643](https://blog.csdn.net/weixin_46016309/article/details/151222643)

\[111] 线程池全面解析:从核心原理到实战配置(含源码分析)\_线程池核心原理-CSDN博客[ https://blog.csdn.net/dom\_jun/article/details/149942439](https://blog.csdn.net/dom_jun/article/details/149942439)

\[112] 从零手写Java线程池到性能优化-51CTO.COM[ https://www.51cto.com/article/818771.html](https://www.51cto.com/article/818771.html)

\[113] 面试必问!Java 线程池 7 大核心机制与源码深挖 - 不夜天 - 博客园[ https://www.cnblogs.com/xuchangqing/articles/19128554](https://www.cnblogs.com/xuchangqing/articles/19128554)

\[114] Java线程池使用场景与ThreadPoolExecutor正确创建方式解析[ https://www.iesdouyin.com/share/video/7537215190165769482/?region=\&mid=7537215336664337191\&u\_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with\_sec\_did=1\&video\_share\_track\_ver=\&titleType=title\&share\_sign=cAxqrhu4Ct9M4hO.zWecAcpCxIF0dg16k8NRxztBcPk-\&share\_version=280700\&ts=1768313618\&from\_aid=1128\&from\_ssr=1\&share\_track\_info=%7B%22link\_description\_type%22%3A%22%22%7D](https://www.iesdouyin.com/share/video/7537215190165769482/?region=\&mid=7537215336664337191\&u_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with_sec_did=1\&video_share_track_ver=\&titleType=title\&share_sign=cAxqrhu4Ct9M4hO.zWecAcpCxIF0dg16k8NRxztBcPk-\&share_version=280700\&ts=1768313618\&from_aid=1128\&from_ssr=1\&share_track_info=%7B%22link_description_type%22%3A%22%22%7D)

\[115] 🏗️ 线程池深度解析：ThreadPoolExecutor底层实现与CompletableFuture异步编程实战[ https://juejin.cn/post/7546745197703823398](https://juejin.cn/post/7546745197703823398)

\[116] ThreadPoolExecutor:execute与submit实战\_java threadpoolexecutor submit-CSDN博客[ https://koudi.blog.csdn.net/article/details/156278796](https://koudi.blog.csdn.net/article/details/156278796)

\[117] 线程池(面试)\_executorservice核心线程数-CSDN博客[ https://blog.csdn.net/jh39456194/article/details/107310043](https://blog.csdn.net/jh39456194/article/details/107310043)

\[118] 自定义线程池 - 月朗星希 - 博客园[ https://www.cnblogs.com/xingxi-du/p/18848060](https://www.cnblogs.com/xingxi-du/p/18848060)

\[119] Java自定义线程池项目实战\_mob64ca1402665b的技术博客\_51CTO博客[ https://blog.51cto.com/u\_16213625/14065097](https://blog.51cto.com/u_16213625/14065097)

\[120] 学习笔记:线程池，反射，网络通信，注解一、线程池 1. 代码示例(企业级最佳实践) java 运行 2. 企业面试题 基 - 掘金[ https://juejin.cn/post/7581297888808402994](https://juejin.cn/post/7581297888808402994)

\[121] Java线程池参数配置与自定义实现解析[ https://www.iesdouyin.com/share/note/7518728836990160154/?region=\&mid=0\&u\_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with\_sec\_did=1\&video\_share\_track\_ver=\&titleType=title\&schema\_type=37\&share\_sign=MFAjtNidO6FsHcuqntYBdU4c9OaY.HfT7slbvwZeczQ-\&share\_version=280700\&ts=1768313618\&from\_aid=1128\&from\_ssr=1\&share\_track\_info=%7B%22link\_description\_type%22%3A%22%22%7D](https://www.iesdouyin.com/share/note/7518728836990160154/?region=\&mid=0\&u_code=0\&did=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&iid=MS4wLjABAAAANwkJuWIRFOzg5uCpDRpMj4OX-QryoDgn-yYlXQnRwQQ\&with_sec_did=1\&video_share_track_ver=\&titleType=title\&schema_type=37\&share_sign=MFAjtNidO6FsHcuqntYBdU4c9OaY.HfT7slbvwZeczQ-\&share_version=280700\&ts=1768313618\&from_aid=1128\&from_ssr=1\&share_track_info=%7B%22link_description_type%22%3A%22%22%7D)

\[122] java 自定义简单的线程池\_潇凝子潇的技术博客\_51CTO博客[ https://blog.51cto.com/u\_4981212/14133798](https://blog.51cto.com/u_4981212/14133798)

\[123] 如何在Java中使用ThreadPoolExecutor自定义线程池-java教程-PHP中文网[ https://m.php.cn/faq/1628273.html](https://m.php.cn/faq/1628273.html)

\[124] Java:实现自定义线程池ThreadPool算法(附带源码)\_自定义线程池实现java-CSDN博客[ https://blog.csdn.net/m0\_61840987/article/details/149775924](https://blog.csdn.net/m0_61840987/article/details/149775924)

> （注：文档部分内容可能由 AI 生成）