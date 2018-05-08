package org.threadly.concurrent;

import java.util.concurrent.Callable;

import org.threadly.concurrent.future.FutureUtils;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.util.ArgumentVerifier;
import org.threadly.util.ExceptionUtils;

/**
 * A {@link SubmitterExecutor} that will run all provided tasks immediately in the same thread 
 * that is calling into it.  This is different from calling the runnable directly only in that no 
 * exceptions will propagate out.  If an exception is thrown it will be provided to 
 * {@link ExceptionUtils#handleException(Throwable)} to In the case of just 
 * {@link #execute(Runnable)} thrown exceptions will be provided to 
 * {@link ExceptionUtils#handleException(Throwable)} to be handled.  Otherwise thrown exceptions 
 * will be represented by their returned {@link ListenableFuture}.
 * 
 * @since 1.2.0
 */
public class SameThreadSubmitterExecutor implements SubmitterExecutor {
  private static final SameThreadSubmitterExecutor INSTANCE;
  
  static {
    INSTANCE = new SameThreadSubmitterExecutor();
  }
  
  /**
   * Call to get a default instance of the {@link SameThreadSubmitterExecutor}.  Because there is 
   * no saved or shared state, the same instance can be reused as much as desired.
   * 
   * @return a static instance of SameThreadSubmitterExecutor
   */
  public static SameThreadSubmitterExecutor instance() {
    return INSTANCE;
  }
  
  @Override
  public void execute(Runnable task) {
    ArgumentVerifier.assertNotNull(task, "task");

    try {
      task.run();
    } catch (Throwable t) {
      ExceptionUtils.handleException(t);
    }
  }

  @Override
  public <T> ListenableFuture<T> submit(Runnable task, T result) {
    ArgumentVerifier.assertNotNull(task, "task");
    
    try {
      task.run();
      
      return FutureUtils.immediateResultFuture(result);
    } catch (Throwable t) {
      return FutureUtils.immediateFailureFuture(t);
    }
  }

  @Override
  public <T> ListenableFuture<T> submit(Callable<T> task) {
    ArgumentVerifier.assertNotNull(task, "task");
    
    try {
      T result = task.call();
      
      return FutureUtils.immediateResultFuture(result);
    } catch (Throwable t) {
      return FutureUtils.immediateFailureFuture(t);
    }
  }
}
