package org.threadly.concurrent;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.ListenableFutureTask;
import org.threadly.concurrent.future.ListenableRunnableFuture;
import org.threadly.concurrent.lock.StripedLock;
import org.threadly.util.ExceptionUtils;

/**
 * <p>TaskDistributor is designed to take a multi-threaded pool
 * and add tasks with a given key such that those tasks will
 * be run single threaded for any given key.  The thread which
 * runs those tasks may be different each time, but no two tasks
 * with the same key will ever be run in parallel.</p>
 * 
 * <p>Because of that, it is recommended that the executor provided 
 * has as many possible threads as possible keys that could be 
 * provided to be run in parallel.  If this class is starved for 
 * threads some keys may continue to process new tasks, while
 * other keys could be starved.</p>
 * 
 * @author jent - Mike Jensen
 */
public class TaskExecutorDistributor {
  protected static final int DEFAULT_THREAD_KEEPALIVE_TIME = 1000 * 10;
  protected static final int DEFAULT_LOCK_PARALISM = 16;
  protected static final float CONCURRENT_HASH_MAP_LOAD_FACTOR = (float)0.75;  // 0.75 is ConcurrentHashMap default
  protected static final int CONCURRENT_HASH_MAP_MAX_INITIAL_SIZE = 100;
  protected static final int CONCURRENT_HASH_MAP_MAX_CONCURRENCY_LEVEL = 100;
  protected static final int ARRAY_DEQUE_INITIAL_SIZE = 8;  // minimum is 8, should be 2^X
  
  protected final Executor executor;
  protected final StripedLock sLock;
  protected final int maxTasksPerCycle;
  private final ConcurrentHashMap<Object, TaskQueueWorker> taskWorkers;
  
  /**
   * Constructor which creates executor based off provided values.
   * 
   * @param expectedParallism Expected number of keys that will be used in parallel
   * @param maxThreadCount Max thread count (limits the qty of keys which are handled in parallel)
   */
  public TaskExecutorDistributor(int expectedParallism, int maxThreadCount) {
    this(expectedParallism, maxThreadCount, Integer.MAX_VALUE);
  }
  
  /**
   * Constructor which creates executor based off provided values.
   * 
   * This constructor allows you to provide a maximum number of tasks for a key before it 
   * yields to another key.  This can make it more fair, and make it so no single key can 
   * starve other keys from running.  The lower this is set however, the less efficient it 
   * becomes in part because it has to give up the thread and get it again, but also because 
   * it must copy the subset of the task queue which it can run.
   * 
   * @param expectedParallism Expected number of keys that will be used in parallel
   * @param maxThreadCount Max thread count (limits the qty of keys which are handled in parallel)
   * @param maxTasksPerCycle maximum tasks run per key before yielding for other keys
   */
  public TaskExecutorDistributor(int expectedParallism, int maxThreadCount, int maxTasksPerCycle) {
    this(new PriorityScheduledExecutor(Math.min(expectedParallism, maxThreadCount), 
                                       maxThreadCount, 
                                       DEFAULT_THREAD_KEEPALIVE_TIME, 
                                       TaskPriority.High, 
                                       PriorityScheduledExecutor.DEFAULT_LOW_PRIORITY_MAX_WAIT_IN_MS), 
         new StripedLock(expectedParallism), maxTasksPerCycle);
  }
  
  /**
   * Constructor to use a provided executor implementation for running tasks.  
   * 
   * This constructs with a default expected level of concurrency of 16.
   * 
   * @param executor A multi-threaded executor to distribute tasks to.  
   *                 Ideally has as many possible threads as keys that 
   *                 will be used in parallel. 
   */
  public TaskExecutorDistributor(Executor executor) {
    this(DEFAULT_LOCK_PARALISM, executor, Integer.MAX_VALUE);
  }
  
  /**
   * Constructor to use a provided executor implementation for running tasks.
   * 
   * This constructor allows you to provide a maximum number of tasks for a key before it 
   * yields to another key.  This can make it more fair, and make it so no single key can 
   * starve other keys from running.  The lower this is set however, the less efficient it 
   * becomes in part because it has to give up the thread and get it again, but also because 
   * it must copy the subset of the task queue which it can run.  
   * 
   * This constructs with a default expected level of concurrency of 16.
   * 
   * @param executor A multi-threaded executor to distribute tasks to.  
   *                 Ideally has as many possible threads as keys that 
   *                 will be used in parallel.
   * @param maxTasksPerCycle maximum tasks run per key before yielding for other keys
   */
  public TaskExecutorDistributor(Executor executor, int maxTasksPerCycle) {
    this(DEFAULT_LOCK_PARALISM, executor, maxTasksPerCycle);
  }
    
  /**
   * Constructor to use a provided executor implementation for running tasks.
   * 
   * @param expectedParallism level of expected qty of threads adding tasks in parallel
   * @param executor A multi-threaded executor to distribute tasks to.  
   *                 Ideally has as many possible threads as keys that 
   *                 will be used in parallel. 
   */
  public TaskExecutorDistributor(int expectedParallism, Executor executor) {
    this(expectedParallism, executor, Integer.MAX_VALUE);
  }
    
  /**
   * Constructor to use a provided executor implementation for running tasks.
   * 
   * This constructor allows you to provide a maximum number of tasks for a key before it 
   * yields to another key.  This can make it more fair, and make it so no single key can 
   * starve other keys from running.  The lower this is set however, the less efficient it 
   * becomes in part because it has to give up the thread and get it again, but also because 
   * it must copy the subset of the task queue which it can run.
   * 
   * @param expectedParallism level of expected qty of threads adding tasks in parallel
   * @param executor A multi-threaded executor to distribute tasks to.  
   *                 Ideally has as many possible threads as keys that 
   *                 will be used in parallel.
   * @param maxTasksPerCycle maximum tasks run per key before yielding for other keys
   */
  public TaskExecutorDistributor(int expectedParallism, Executor executor, 
                                 int maxTasksPerCycle) {
    this(executor, new StripedLock(expectedParallism), 
         maxTasksPerCycle);
  }
  
  /**
   * Constructor to be used in unit tests.
   * 
   * This constructor allows you to provide a maximum number of tasks for a key before it 
   * yields to another key.  This can make it more fair, and make it so no single key can 
   * starve other keys from running.  The lower this is set however, the less efficient it 
   * becomes in part because it has to give up the thread and get it again, but also because 
   * it must copy the subset of the task queue which it can run.
   * 
   * @param executor executor to be used for task worker execution 
   * @param sLock lock to be used for controlling access to workers
   * @param maxTasksPerCycle maximum tasks run per key before yielding for other keys
   */
  protected TaskExecutorDistributor(Executor executor, StripedLock sLock, 
                                    int maxTasksPerCycle) {
    if (executor == null) {
      throw new IllegalArgumentException("executor can not be null");
    } else if (sLock == null) {
      throw new IllegalArgumentException("striped lock must be provided");
    } else if (maxTasksPerCycle < 1) {
      throw new IllegalArgumentException("maxTasksPerCycle must be > 0");
    }
    
    this.executor = executor;
    this.sLock = sLock;
    this.maxTasksPerCycle = maxTasksPerCycle;
    int mapInitialSize = Math.min(sLock.getExpectedConcurrencyLevel(), 
                                  CONCURRENT_HASH_MAP_MAX_INITIAL_SIZE);
    int mapConcurrencyLevel = Math.min(sLock.getExpectedConcurrencyLevel(), 
                                       CONCURRENT_HASH_MAP_MAX_CONCURRENCY_LEVEL);
    this.taskWorkers = new ConcurrentHashMap<Object, TaskQueueWorker>(mapInitialSize,  
                                                                      CONCURRENT_HASH_MAP_LOAD_FACTOR, 
                                                                      mapConcurrencyLevel);
  }
  
  /**
   * Getter for the executor being used behind the scenes.
   * 
   * @return executor tasks are being distributed to
   */
  public Executor getExecutor() {
    return executor;
  }
  
  /**
   * Returns an executor implementation where all tasks submitted 
   * on this executor will run on the provided key.
   * 
   * @param threadKey object key where hashCode will be used to determine execution thread
   * @return executor which will only execute based on the provided key
   */
  public Executor getExecutorForKey(Object threadKey) {
    return getSubmitterForKey(threadKey);
  }
  
  /**
   * Returns a {@link SubmitterExecutorInterface} implementation where all tasks 
   * submitted on this executor will run on the provided key.
   * 
   * @param threadKey object key where hashCode will be used to determine execution thread
   * @return executor which will only execute based on the provided key
   */
  public SubmitterExecutorInterface getSubmitterForKey(Object threadKey) {
    if (threadKey == null) {
      throw new IllegalArgumentException("Must provide thread key");
    }
    
    return new KeyBasedSubmitter(threadKey);
  }
  
  /**
   * Provide a task to be run with a given thread key.
   * 
   * @param threadKey object key where hashCode will be used to determine execution thread
   * @param task Task to be executed.
   */
  public void addTask(Object threadKey, Runnable task) {
    if (threadKey == null) {
      throw new IllegalArgumentException("Must provide thread key");
    } else if (task == null) {
      throw new IllegalArgumentException("Must provide task");
    }
    
    Object agentLock = sLock.getLock(threadKey);
    synchronized (agentLock) {
      TaskQueueWorker worker = taskWorkers.get(threadKey);
      if (worker == null) {
        worker = new TaskQueueWorker(threadKey, agentLock, task);
        taskWorkers.put(threadKey, worker);
        executor.execute(worker);
      } else {
        worker.add(task);
      }
    }
  }
  
  /**
   * Submit a task to be run with a given thread key.
   * 
   * @param threadKey object key where hashCode will be used to determine execution thread
   * @param task Task to be executed.
   * @return Future to represent when the execution has occurred
   */
  public ListenableFuture<?> submitTask(Object threadKey, Runnable task) {
    return submitTask(threadKey, task, null);
  }
  
  /**
   * Submit a task to be run with a given thread key.
   * 
   * @param threadKey object key where hashCode will be used to determine execution thread
   * @param task Runnable to be executed.
   * @param result Result to be returned from future when task completes
   * @return Future to represent when the execution has occurred and provide the given result
   */
  public <T> ListenableFuture<T> submitTask(Object threadKey, Runnable task, 
                                            T result) {
    if (threadKey == null) {
      throw new IllegalArgumentException("Must provide thread key");
    } else if (task == null) {
      throw new IllegalArgumentException("Must provide task");
    }
    
    ListenableRunnableFuture<T> rf = new ListenableFutureTask<T>(false, task, result);
    
    addTask(threadKey, rf);
    
    return rf;
  }
  
  /**
   * Submit a callable to be run with a given thread key.
   * 
   * @param threadKey object key where hashCode will be used to determine execution thread
   * @param task Callable to be executed.
   * @return Future to represent when the execution has occurred and provide the result from the callable
   */
  public <T> ListenableFuture<T> submitTask(Object threadKey, Callable<T> task) {
    if (threadKey == null) {
      throw new IllegalArgumentException("Must provide thread key");
    } else if (task == null) {
      throw new IllegalArgumentException("Must provide task");
    }
    
    ListenableRunnableFuture<T> rf = new ListenableFutureTask<T>(false, task);
    
    addTask(threadKey, rf);
    
    return rf;
  }
  
  /**
   * <p>Worker which will consume through a given queue of tasks.
   * Each key is represented by one worker at any given time.</p>
   * 
   * @author jent - Mike Jensen
   */
  private class TaskQueueWorker implements Runnable {
    private final Object mapKey;
    private final Object agentLock;
    private Queue<Runnable> queue;
    
    private TaskQueueWorker(Object mapKey, 
                            Object agentLock, 
                            Runnable firstTask) {
      this.mapKey = mapKey;
      this.agentLock = agentLock;
      this.queue = new ArrayDeque<Runnable>(ARRAY_DEQUE_INITIAL_SIZE);
      queue.add(firstTask);
    }
    
    public void add(Runnable task) {
      queue.add(task);
    }
    
    @Override
    public void run() {
      int consumedItems = 0;
      while (true) {
        Queue<Runnable> nextList;
        synchronized (agentLock) {
          if (queue.isEmpty()) {  // nothing left to run
            taskWorkers.remove(mapKey);
            break;
          } else {
            if (consumedItems < maxTasksPerCycle) {
              if (queue.size() + consumedItems <= maxTasksPerCycle) {
                // we can run the entire next queue
                nextList = queue;
                queue = new ArrayDeque<Runnable>(ARRAY_DEQUE_INITIAL_SIZE);
              } else {
                // we need to run a subset of the queue, so copy and remove what we can run
                int nextListSize = maxTasksPerCycle - consumedItems;
                nextList = new ArrayDeque<Runnable>(nextListSize);
                Iterator<Runnable> it = queue.iterator();
                do {
                  nextList.add(it.next());
                  it.remove();
                } while (nextList.size() < nextListSize);
              }
              
              consumedItems += nextList.size();
            } else {
              // re-execute this worker to give other works a chance to run
              executor.execute(this);
              /* notice that we never removed from taskWorkers, and thus wont be
               * executed from people adding new tasks 
               */
              break;
            }
          }
        }
        
        Iterator<Runnable> it = nextList.iterator();
        while (it.hasNext()) {
          try {
            Runnable next = it.next();
            next.run();
          } catch (Throwable t) {
            ExceptionUtils.handleException(t);
          }
        }
      }
    }
  }
  
  /**
   * <p>Simple {@link SubmitterExecutorInterface} implementation 
   * that runs on a given key.</p>
   * 
   * @author jent - Mike Jensen
   */
  protected class KeyBasedSubmitter implements SubmitterExecutorInterface {
    protected final Object threadKey;
    
    protected KeyBasedSubmitter(Object threadKey) {
      this.threadKey = threadKey;
    }
    
    @Override
    public void execute(Runnable command) {
      addTask(threadKey, command);
    }

    @Override
    public ListenableFuture<?> submit(Runnable task) {
      return submitTask(threadKey, task);
    }

    @Override
    public <T> ListenableFuture<T> submit(Runnable task, T result) {
      return submitTask(threadKey, task, result);
    }

    @Override
    public <T> ListenableFuture<T> submit(Callable<T> task) {
      return submitTask(threadKey, task);
    }
  }
}
