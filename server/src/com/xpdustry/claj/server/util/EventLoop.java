package com.xpdustry.claj.server.util;

import arc.func.Cons;
import arc.util.Log;
import arc.util.TaskQueue;


/** Simple threaded event loop system. */
public class EventLoop {
  protected final TaskQueue queue = new TaskQueue();
  protected Thread thread;
  protected boolean started;
  public Cons<Throwable> errorHandler = Log::err;

  public EventLoop() {
    this(false);
  }
  
  public EventLoop(boolean daemon) {
    thread = new Thread(this::mainLoop, "EventLoop");
    thread.setDaemon(daemon);
    thread.setUncaughtExceptionHandler((t, e) -> Log.err(t.getName(), e));
  }

  protected synchronized void mainLoop() {
    try {
      started = true;
      while (!thread.isInterrupted()) {
        wait();
        run();
      } 
    } catch (InterruptedException ignored) {}
  }
  
  /** Runs all awaiting tasks. */
  public void run() {
    queue.run();
  }
  
  /** Post a task in the event loop. */
  public synchronized void post(Runnable task) {
    if (!started) throw new IllegalStateException("event loop not started; please use #start() before.");
    if (!thread.isAlive()) throw new IllegalStateException("event loop is not running.");
   
    queue.post(task);
    notify();
  }
  
  /** Same as {@link #post(Runnable)} but catch errors instead of crashing the event loop */
  public void postSafe(Runnable task) {
    post(() -> {
      try { task.run(); }
      catch (Exception e) { errorHandler.get(e); }
    });
  }
  
  /** Starts the event loop thread. */
  public void start() {
    thread.start();
  }
  
  /** @return if event loop is running or not */
  public boolean running() {
    return thread.isAlive();
  }
  
  public void stop() {
    stop(false);
  }
  
  /** 
   * Requests the event loop to stop. <br>
   * Remaining tasks may not be runs after that.
   * @param wait waits for running tasks to be finished
   */
  public void stop(boolean wait) {
    thread.interrupt();
    if (wait) {
      try { thread.join(); } 
      catch (InterruptedException ignored) {}
    }
  }
}
