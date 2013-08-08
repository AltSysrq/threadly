package org.threadly.concurrent;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.threadly.concurrent.lock.VirtualLock;
import org.threadly.util.ExceptionUtils;

/**
 * This is a future which can be executed.  Allowing you to construct the future with 
 * the interior work, submit it to an executor, and then return this future.
 * 
 * @author jent - Mike Jensen
 * @param <T> type of future implementation
 */
public class RunnableFuture<T> implements ListenableFuture<T>, Runnable {
  private final Runnable runnable;
  private final T runnableResult;
  private final Callable<T> callable;
  private final VirtualLock lock;
  private final Map<Runnable, Executor> listeners;
  private boolean canceled;
  private boolean started;
  private boolean done;
  private Throwable failure;
  private T result;
  
  protected RunnableFuture(Runnable task, T result, VirtualLock lock) {
    this.runnable = task;
    this.runnableResult = result;
    callable = null;
    this.lock = lock;
    listeners = new HashMap<Runnable, Executor>();
    canceled = false;
    started = false;
    done = false;
    failure = null;
    result = null;
  }
  
  protected RunnableFuture(Callable<T> task, VirtualLock lock) {
    this.runnable = null;
    this.runnableResult = null;
    this.callable = task;
    this.lock = lock;
    listeners = new HashMap<Runnable, Executor>();
    canceled = false;
    started = false;
    done = false;
    failure = null;
    result = null;
  }
  
  private void callListeners() {
    synchronized (lock) {
      Iterator<Entry<Runnable, Executor>> it = listeners.entrySet().iterator();
      while (it.hasNext()) {
        Entry<Runnable, Executor> listener = it.next();
        runListener(listener.getKey(), listener.getValue(), false);
      }
      
      listeners.clear();
    }
  }
  
  private void runListener(Runnable listener, Executor executor, 
                           boolean throwException) {
    if (executor != null) {
      executor.execute(listener);
    } else {
      try {
        listener.run();
      } catch (RuntimeException e) {
        if (throwException) {
          throw e;
        } else {
          UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
          if (handler != null) {
            handler.uncaughtException(Thread.currentThread(), e);
          } else {
            e.printStackTrace();
          }
        }
      }
    }
  }
  
  @Override
  public void run() {
    try {
      boolean shouldRun = false;
      synchronized (lock) {
        if (! canceled) {
          started = true;
          shouldRun = true;
        }
      }
      
      if (shouldRun) {
        if (runnable != null) {
          runnable.run();
          result = runnableResult;
        } else {
          result = callable.call();
        }
      }
      
      synchronized (lock) {
        done = true;
      
        callListeners();
        
        lock.signalAll();
      }
    } catch (Throwable t) {
      synchronized (lock) {
        done = true;
        failure = t;
      
        callListeners();
      
        lock.signalAll();
      }
      
      throw ExceptionUtils.makeRuntime(t);
    }
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    synchronized (lock) {
      canceled = true;
      
      callListeners();
      
      lock.signalAll();
    
      return ! started;
    }
  }

  @Override
  public boolean isDone() {
    synchronized (lock) {
      return done;
    }
  }

  @Override
  public boolean isCancelled() {
    synchronized (lock) {
      return canceled && ! started;
    }
  }

  @Override
  public T get() throws InterruptedException, ExecutionException {
    try {
      return get(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      // basically impossible
      throw ExceptionUtils.makeRuntime(e);
    }
  }

  @Override
  public T get(long timeout, TimeUnit unit) throws InterruptedException,
                                                   ExecutionException,
                                                   TimeoutException {
    long startTime = ClockWrapper.getAccurateTime();
    long timeoutInMs = TimeUnit.MILLISECONDS.convert(timeout, unit);
    synchronized (lock) {
      long waitTime = timeoutInMs - (ClockWrapper.getAccurateTime() - startTime);
      while (! done && waitTime > 0) {
        lock.await(waitTime);
        waitTime = timeoutInMs - (ClockWrapper.getAccurateTime() - startTime);
      }
      
      if (canceled) {
        throw new CancellationException();
      } else if (failure != null) {
        throw new ExecutionException(failure);
      } else if (! done) {
        throw new TimeoutException();
      }
      
      return result;
    }
  }

  @Override
  public void addListener(Runnable listener) {
    addListener(listener, null);
  }

  @Override
  public void addListener(Runnable listener, Executor executor) {
    synchronized (lock) {
      if (done || canceled) {
        runListener(listener, executor, true);
      } else {
        listeners.put(listener, executor);
      }
    }
  }
}