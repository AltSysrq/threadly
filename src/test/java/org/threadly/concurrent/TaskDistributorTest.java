package org.threadly.concurrent;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.test.TestCondition;

@SuppressWarnings("javadoc")
public class TaskDistributorTest {
  private static final int PARALLEL_LEVEL = 100;
  private static final int RUNNABLE_COUNT_PER_LEVEL = 5000;
  
  private volatile boolean ready;
  private PriorityScheduledExecutor scheduler;
  private Object agentLock;
  private TaskDistributor distributor;
  
  @Before
  public void setup() {
    scheduler = new PriorityScheduledExecutor(PARALLEL_LEVEL + 1, 
                                              PARALLEL_LEVEL * 2, 
                                              1000 * 10, 
                                              TaskPriority.High, 
                                              PriorityScheduledExecutor.DEFAULT_LOW_PRIORITY_MAX_WAIT);
    agentLock = new Object();
    distributor = new TaskDistributor(scheduler, agentLock);
    ready = false;
  }
  
  @After
  public void tearDown() {
    scheduler.shutdown();
    scheduler = null;
    agentLock = null;
    distributor = null;
    ready = false;
  }
  
  @Test
  public void testGetExecutor() {
    assertTrue(scheduler == distributor.getExecutor());
  }
  
  @Test
  public void testExecutes() {
    final List<TestRunnable> runs = new ArrayList<TestRunnable>(PARALLEL_LEVEL * RUNNABLE_COUNT_PER_LEVEL);

    scheduler.execute(new Runnable() {
      @Override
      public void run() {
        // hold agent lock to prevent execution till ready
        synchronized (agentLock) {
          for (int i = 0; i < PARALLEL_LEVEL; i++) {
            Object key = new Object();
            ThreadContainer tc = new ThreadContainer();
            for (int j = 0; j < RUNNABLE_COUNT_PER_LEVEL; j++) {
              TestRunnable tr = new TestRunnable(tc);
              runs.add(tr);
              distributor.addTask(key, tr);
            }
          }
          
          ready = true;
        }
      }
    });
    
    // block till thread starts and runs is populated
    new TestCondition() {
      @Override
      public boolean get() {
        return ready;
      }
    }.blockTillTrue();

    final TestRunnable lastRunnable;
    synchronized (agentLock) {
      lastRunnable = runs.get(runs.size() - 1);
    }
    
    // block till last runnable is run
    new TestCondition() {
      @Override
      public boolean get() {
        return lastRunnable.ranCount >= 1;
      }
    }.blockTillTrue();
    
    Iterator<TestRunnable> it = runs.iterator();
    while (it.hasNext()) {
      TestRunnable tr = it.next();
      assertEquals(tr.ranCount, 1); // verify each only ran once
      assertTrue(tr.threadTracker.threadConsistent);  // verify that all threads for a given key ran in the same thread
    }
  }
  
  private class TestRunnable implements Runnable {
    private final ThreadContainer threadTracker;
    private int ranCount = 0;
    
    private TestRunnable(ThreadContainer threadTracker) {
      this.threadTracker = threadTracker;
    }
    
    @Override
    public void run() {
      ranCount++;
      threadTracker.running();
    }
  }
  
  private class ThreadContainer {
    private Thread runningThread = null;
    private boolean threadConsistent = true;
    
    public synchronized void running() {
      if (runningThread == null) {
        runningThread = Thread.currentThread();
      } else {
        threadConsistent = threadConsistent && runningThread.equals(Thread.currentThread());
      }
    }
  }
}
