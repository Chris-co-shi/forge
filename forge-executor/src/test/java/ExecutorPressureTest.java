import com.indigo.executor.Executor;
import com.indigo.executor.SimpleExecutor;

/**
 * @author 史偕成
 * @date 2026/01/13 21:41
 **/
public class ExecutorPressureTest {

    public static void main(String[] args) {
        Executor simpleExecutor = new SimpleExecutor();
        for (int i = 0; i < 100000; i++) {
            simpleExecutor.submit(() -> {
                try {
                    System.out.println("Thread Name : "+ Thread.currentThread().getName());
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    e.fillInStackTrace();
                }
            });
        }
    }
}
