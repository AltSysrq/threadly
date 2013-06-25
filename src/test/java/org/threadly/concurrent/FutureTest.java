package org.threadly.concurrent;

import static org.junit.Assert.*;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.threadly.concurrent.lock.NativeLock;
import org.threadly.concurrent.lock.VirtualLock;
import org.threadly.test.concurrent.TestRunnable;
import org.threadly.test.concurrent.TestUtils;
import org.threadly.test.concurrent.TestablePriorityScheduler;

@SuppressWarnings("javadoc")
public class FutureTest {
  private static final TestablePriorityScheduler scheduler;
  
  static {
    Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
      @Override
      public void uncaughtException(Thread t, Throwable e) {
        // ignored
      }
    });
    
    scheduler = new TestablePriorityScheduler(new PriorityScheduledExecutor(10, 10, 500));
  }
  
  public static void blockTillCompletedTest(FutureFactory ff) {
    Runnable r = new TestRunnable();
    Future<?> future = ff.make(r, scheduler.makeLock());
    BlockingRunnable br = new BlockingRunnable(future);
    
    scheduler.execute(br);
    
    scheduler.tick();
    assertTrue(br.runStarted);
    assertFalse(br.blockReleased);
    assertFalse(br.executionExceptionThrown);
    
    scheduler.execute((Runnable)future);
    
    scheduler.tick();
    assertTrue(br.runStarted);
    assertTrue(br.blockReleased);
    assertFalse(br.executionExceptionThrown);
    
    assertFalse(future.isCancelled());
    assertTrue(future.isDone());
  }
  
  public static void blockTillCompletedFail(FutureFactory ff) {
    Runnable r = new FailureRunnable();
    Future<?> future = ff.make(r, scheduler.makeLock());
    BlockingRunnable br = new BlockingRunnable(future);
    
    scheduler.execute(br);
    
    scheduler.tick();
    assertTrue(br.runStarted);
    assertFalse(br.blockReleased);
    assertFalse(br.executionExceptionThrown);
    
    scheduler.execute((Runnable)future);
    
    scheduler.tick();
    assertTrue(br.runStarted);
    assertTrue(br.blockReleased);
    assertTrue(br.executionExceptionThrown);
    
    assertFalse(future.isCancelled());
    assertTrue(future.isDone());
  }
  
  public static void cancelTest(FutureFactory ff) {
    Runnable r = new TestRunnable();
    Future<?> future = ff.make(r, scheduler.makeLock());
    BlockingRunnable br = new BlockingRunnable(future);
    
    scheduler.execute(br);
    
    scheduler.tick();
    assertTrue(br.runStarted);
    assertFalse(br.blockReleased);
    assertFalse(br.executionExceptionThrown);
    
    // cancel
    future.cancel(false);
    
    scheduler.execute((Runnable)future);
    
    // assert get released
    scheduler.tick();
    assertTrue(br.runStarted);
    assertTrue(br.blockReleased);
    assertFalse(br.executionExceptionThrown);
    
    assertTrue(future.isCancelled());
    assertTrue(future.isDone());
  }
  
  public static void getTimeoutFail(FutureFactory ff) throws InterruptedException, ExecutionException {
    final int timeout = 10;
    final int threadSleepTime = 1000 * 10;

    Future<?> future = ff.make(new TestRunnable() {
      @Override
      public void handleRunStart() {
        TestUtils.sleep(threadSleepTime);
      }
    }, new NativeLock());
    
    scheduler.getExecutor().execute((Runnable)future);
    
    long startTime = System.currentTimeMillis();
    try {
      future.get(timeout, TimeUnit.MILLISECONDS);
      fail("Exception should have been thrown");
    } catch (TimeoutException e) {
      long catchTime = System.currentTimeMillis();
      assertTrue(catchTime - startTime >= timeout);
    }
  }
  
  public static void isDoneTest(FutureFactory ff) {
    TestRunnable r = new TestRunnable();
    Future<?> future = ff.make(r, new NativeLock());
    scheduler.getExecutor().execute((Runnable)future);
    try {
      future.get();
    } catch (InterruptedException e) {
      // ignored
    } catch (ExecutionException e) {
      // ignored
    }
    
    assertTrue(future.isDone());
  }
  
  public static void isDoneFail(FutureFactory ff) {
    TestRunnable r = new FailureRunnable();
    Future<?> future = ff.make(r, new NativeLock());
    scheduler.getExecutor().execute((Runnable)future);
    try {
      future.get();
    } catch (InterruptedException e) {
      // ignored
    } catch (ExecutionException e) {
      // ignored
    }
    
    assertTrue(future.isDone());
  }
  
  public interface FutureFactory {
    public Future<?> make(Runnable run, 
                          VirtualLock lock);
    public <T> Future<T> make(Callable<T> callable, 
                              VirtualLock lock);
  }
  
  private static class BlockingRunnable extends VirtualRunnable {
    private final Future<?> future;
    private volatile boolean runStarted;
    private volatile boolean blockReleased;
    private volatile boolean executionExceptionThrown;
    
    public BlockingRunnable(Future<?> future) {
      this.future = future;
      runStarted = false;
      blockReleased = false;
      executionExceptionThrown = false;
    }

    @Override
    public void run() {
      runStarted = true;
      try {
        future.get();
      } catch (InterruptedException e) {
        // ignored
      } catch (ExecutionException e) {
        executionExceptionThrown = true;
        // ignored
      } finally {
        blockReleased = true;
      }
    }
  }
  
  private static class FailureRunnable extends TestRunnable {
    @Override
    public void handleRunFinish() {
      throw new RuntimeException();
    }
  }
}
