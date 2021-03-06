package org.threadly.concurrent.wrapper.limiter;

import org.threadly.concurrent.PrioritySchedulerTest.PrioritySchedulerFactory;
import org.threadly.concurrent.SubmitterExecutor;
import org.threadly.concurrent.SubmitterExecutorInterfaceTest;

@SuppressWarnings("javadoc")
public class KeyedExecutorLimiterInterfaceTest extends SubmitterExecutorInterfaceTest {
  @Override
  protected SubmitterExecutorFactory getSubmitterExecutorFactory() {
    return new KeyedExecutorLimiterFactory();
  }
  
  private static class KeyedExecutorLimiterFactory implements SubmitterExecutorFactory {
    private final PrioritySchedulerFactory schedulerFactory = new PrioritySchedulerFactory();
    
    @Override
    public SubmitterExecutor makeSubmitterExecutor(int poolSize, boolean prestartIfAvailable) {
      SubmitterExecutor executor = schedulerFactory.makeSubmitterExecutor(poolSize * 2, prestartIfAvailable);
      
      return new KeyedExecutorLimiter(executor, poolSize).getSubmitterExecutorForKey(new Object());
    }

    @Override
    public void shutdown() {
      schedulerFactory.shutdown();
    }
  }
}
