package plugins.WebOfTrust.util;

import freenet.node.FastRunnable;
import freenet.node.PrioRunnable;
import freenet.support.Executor;
import freenet.support.Ticker;
import freenet.support.io.NativeThread;

/**
 * A {@link DelayedBackgroundJob} implementation that uses a {@link Ticker} for scheduling.
 *
 * @author bertm
 * @see TickerDelayedBackgroundJobFactory
 */
public class TickerDelayedBackgroundJob implements DelayedBackgroundJob {
    /** Job wrapper for status tracking. */
    private final DelayedBackgroundRunnable realJob;
    /** Human-readable name of this job. */
    private final String name;
    /** Aggregation delay in milliseconds. */
    private final long defaultDelay;
    /** The ticker used to schedule this job. */
    private final Ticker ticker;
    /** The executor of this background job. */
    private final Executor executor;


    /**
     * TODO: Code quality: To ease understanding of the whole of TickerDelayedBackgroundJob, and to
     * help with testing, add a member function which contains assert()s for all the member
     * variables of TickerDelayedBackgroundJob. Each assert() should demonstrate the expected values
     * of the member variable in the given state.
     * <pre><code>
     * public boolean validate() {
     *   switch(this) {
     *     case IDLE:
     *       assert(waitingTickerJob == null)
     *     ...
     *   }
     *   // Return boolean so we can contain the call to this function in an assert for performance
     *   return true;
     * }
     * </code></pre>
     * Notice: You will have to remove the attribute "static" from the enum so you can access the
     * members of the TickerDelayedBackgroundJob.
     */
    static enum JobState {
        /** Waiting for a trigger, no running job thread or scheduled job. */
        IDLE,
        /** Waiting for the scheduled job to be executed, no running job thread. */
        WAITING,
        /** Running the job in the job thread. */
        RUNNING,
        /** Waiting for the running job thread to finish before we terminate. */
        TERMINATING,
        /** Terminated, no running job thread. */
        TERMINATED
    }
    /** Running state of this job. */
    private JobState state = JobState.IDLE;

    /** Tiny job to run on the ticker, invoking the execution of the {@link #realJob} on the
     * executor (only set in {@link JobState#WAITING}). */
    private Runnable waitingTickerJob = null;

    /** Job execution thread, only if the job is running (only set in {@link JobState#RUNNING}). */
    private Thread thread = null;


    /** Constant for {@link #nextExecutionTime} meaning there is no next execution requested. */
    private final static long NO_EXECUTION = Long.MAX_VALUE;
    /** Next absolute time we have a job execution scheduled, or {@link #NO_EXECUTION} if none. */
    private long nextExecutionTime = NO_EXECUTION;


    /**
     * Constructs a delayed background job with the given default delay. Negative delays
     * are treated as zero delay.
     * The {@link Ticker} given and its {@link Executor} <b>must</b> have an asynchronous
     * implementation of respectively
     * {@link Ticker#queueTimedJob(Runnable, String, long, boolean, boolean) queueTimedJob} and
     * {@link Executor#execute(Runnable, String) execute}. When this background job is
     * {@link BackgroundJob#terminate() terminated}, the running job will be notified by means of
     * interruption of its thread. Hence, the job implementer must take care not to swallow
     * {@link InterruptedException}, or for long computations, periodically check the
     * {@link Thread#interrupted()} flag of its {@link Thread#currentThread() thread} and exit
     * accordingly.
     * @param job the job to run in the background
     * @param name a human-readable name for the job
     * @param delayMillis the default background job aggregation delay in milliseconds
     * @param ticker an asynchronous ticker with asynchronous executor
     *
     * @see TickerDelayedBackgroundJobFactory
     */
    public TickerDelayedBackgroundJob(Runnable job, String name, long delayMillis, Ticker ticker) {
        if (job == null || name == null || ticker == null || ticker.getExecutor() == null) {
            throw new NullPointerException();
        }
        if (delayMillis < 0) {
            delayMillis = 0;
        }
        this.realJob = new DelayedBackgroundRunnable(job);
        this.name = name;
        this.defaultDelay = delayMillis;
        this.ticker = ticker;
        this.executor = ticker.getExecutor();
    }

    /**
     * Triggers scheduling of the job with the default delay if no job is scheduled. If a job
     * is already scheduled later than the default delay, it is rescheduled at the default delay.
     *
     * The first trigger received after the start of the last job execution leads to scheduling of
     * another execution of the job, either after the default delay or when the currently executing
     * job is finished, whichever comes last. A newly constructed delayed background job can be
     * assumed to have started its last job infinitely in the past.
     * @see #triggerExecution(long)
     */
    @Override
    public synchronized void triggerExecution() {
        triggerExecution(defaultDelay);
    }

    /**
     * Triggers scheduling of the job with the given delay if no job is scheduled. If a job
     * is already scheduled later than the given delay, it is rescheduled at the given delay.
     * Negative delays are treated as to zero delays.
     *
     * The first trigger received after the start of the last job execution leads to scheduling of
     * another execution of the job, either after the default delay or when the currently executing
     * job is finished, whichever comes last. A newly constructed delayed background job can be
     * assumed to have started its last job infinitely in the past.
     * @param delayMillis the maximum trigger aggregation delay in milliseconds
     * @see #triggerExecution()
     */
    @Override
    public synchronized void triggerExecution(long delayMillis) {
        tryEnqueue(delayMillis);
    }

    @Override
    public synchronized void terminate() {
        switch (state) {
            case TERMINATED:
                assert(waitingTickerJob == null) : "having ticker job in TERMINATED state";
                assert(thread == null) : "having job thread in TERMINATED state";
                return;
            case TERMINATING:
                assert(waitingTickerJob == null) : "having ticker job in TERMINATING state";
                assert(thread != null) : "TERMINATING state but no thread";
                return;
            case IDLE:
                toTERMINATED();
                return;
            case WAITING:
                toTERMINATED();
                return;
            case RUNNING:
                toTERMINATING();
                return;
        }
    }

    @Override
    public synchronized boolean isTerminated() {
        return state == JobState.TERMINATED;
    }

    @Override
    public synchronized void waitForTermination(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while(timeoutMillis > 0 && state != JobState.TERMINATED) {
            wait(timeoutMillis);
            timeoutMillis = deadline - System.currentTimeMillis();
        }
    }

    /**
     * Implementation of {@link #triggerExecution(long)}.
     * Caller must ensure synchronization on {@code this}.
     */
    private void tryEnqueue(long delayMillis) {
        if (state == JobState.TERMINATING || state == JobState.TERMINATED) {
            return;
        }
        if (delayMillis < 0) {
            delayMillis = 0;
        }
        long newExecutionTime = System.currentTimeMillis() + delayMillis;
        if (newExecutionTime < nextExecutionTime) {
            nextExecutionTime = newExecutionTime;
            if (state == JobState.RUNNING) {
                // Will automatically schedule this run when the running job finishes.
                return;
            }
            enqueueWaitingTickerJob(delayMillis);
        }
    }

    /**
     * Schedules the ticker job (which will submit the background job to the executor) at the
     * ticker with the given delay, or executes the ticker job immediately if the delay is zero.
     * If the current state is {@link JobState#IDLE}, the state is changed to
     * {@link JobState#WAITING} and a new ticker job is created.
     * Caller must ensure synchronization on {@code this}.
     * @param delayMillis the delay in milliseconds
     */
    private void enqueueWaitingTickerJob(long delayMillis) {
        assert(state == JobState.IDLE || state == JobState.WAITING) :
                "enqueueing ticker job in non-IDLE and non-WAITING state";
        if (state == JobState.WAITING) {
            // Best-effort attempt at removing the stale job to be replaced; this fails if the job
            // has already been removed from the ticker queue.
            ticker.removeQueuedJob(waitingTickerJob);
            // Replace the ticker job in case the above fails, so the stale job will not run.
            waitingTickerJob = createTickerJob();
        }
        if (state == JobState.IDLE) {
            toWAITING();
        }
        if (delayMillis > 0) {
            ticker.queueTimedJob(waitingTickerJob, name + " (waiting)", delayMillis, true, false);
        } else {
            waitingTickerJob.run();
        }
    }

    /**
     * Creates a tiny job that instructs the executor to run the background job immediately, to
     * be executed by the ticker.
     */
    private Runnable createTickerJob() {
        // Use FastRunnable here so hopefully the Ticker will execute this on its main thread
        // instead of spawning a new thread for this.
        return new FastRunnable() {
            @Override
            public void run() {
                synchronized(TickerDelayedBackgroundJob.this) {
                    // If this runnable is not the waitingTickerJob, it has been rescheduled. Only
                    // run if this is the expected ticker job, otherwise the expected job will run
                    // real soon.
                    if (this == waitingTickerJob) {
                        executor.execute(realJob, name + " (running)");
                    }
                }
            }
        };
    }

    /**
     * {RUNNING} -> IDLE
     */
    private void toIDLE() {
        assert(state == JobState.RUNNING) : "going to IDLE from non-RUNNING state";
        assert(thread == Thread.currentThread()) : "going to IDLE from non-job thread";
        assert(waitingTickerJob == null) : "having ticker job while going to IDLE state";
        thread = null;
        state = JobState.IDLE;
    }

    /**
     * {IDLE} -> WAITING
     */
    private void toWAITING() {
        assert(state == JobState.IDLE) : "going to WAITING from non-IDLE state";
        assert(thread == null) : "having job thread while going to WAITING state";
        assert(waitingTickerJob == null) : "having ticker job while going to WAITING state";
        // Use a unique job for each (re)scheduling to avoid running twice.
        waitingTickerJob = createTickerJob();
        state = JobState.WAITING;
    }

    /**
     * {WAITING} -> RUNNING
     */
    private void toRUNNING() {
        assert(state == JobState.WAITING) : "going to RUNNING state from non-WAITING state";
        assert(thread == null) : "already having job thread while going to RUNNING state";
        assert(waitingTickerJob != null) : "going to RUNNING state without ticker job";
        assert(nextExecutionTime <= System.currentTimeMillis());
        waitingTickerJob = null;
        nextExecutionTime = NO_EXECUTION;
        thread = Thread.currentThread();
        state = JobState.RUNNING;
    }

    /**
     * {RUNNING} -> TERMINATING
     */
    private void toTERMINATING() {
        assert(state == JobState.RUNNING) : "going to TERMINATING state from non-RUNNING state";
        assert(thread != null) : "going to TERMINATING state without job thread";
        assert(waitingTickerJob == null) : "having ticker job while going to TERMINATING state";
        thread.interrupt();
        state = JobState.TERMINATING;
    }

    /**
     * {IDLE, TERMINATING, WAITING} -> TERMINATED
     */
    private void toTERMINATED() {
        if (state == JobState.TERMINATING) {
            assert(thread == Thread.currentThread()) : "going to TERMINATED from non-job thread";
            assert(waitingTickerJob == null) : "going to TERMINATED with waiting ticker job";
            thread = null;
        } else if (state == JobState.WAITING) {
            assert(thread == null) : "having job thread while going to TERMINATED state";
            assert(waitingTickerJob != null) : "in WAITING state but no ticker job";
            // Remove the scheduled job from the ticker on a best-effort basis.
            ticker.removeQueuedJob(waitingTickerJob);
            waitingTickerJob = null;
        } else {
            assert(state == JobState.IDLE) : "going to TERMINATED state from illegal state";
            assert(thread == null) : "having job thread while going to TERMINATED state";
            assert(waitingTickerJob == null) : "going to TERMINATED with waiting ticker job";
        }
        state = JobState.TERMINATED;
        // Notify all threads waiting in waitForTermination()
        notifyAll();
    }

    /**
     * Indicates whether this job may start and sets the state to {@link JobState#RUNNING}
     * accordingly. If this job is in {@link JobState#TERMINATED}, it may not start, if it is
     * in {@link JobState#WAITING}, it may start.
     * @return {@code true} if the job may start
     */
    private synchronized boolean onJobStarted() {
        if (state == JobState.TERMINATED) {
            return false;
        }
        toRUNNING();
        return true;
    }

    /**
     * Finishes the job by either enqueuing itself again in {@link JobState#WAITING} (if there has
     * been a trigger since the start of the last job), waiting for a trigger in
     * {@link JobState#IDLE} or going to {@link JobState#TERMINATED} if we were previously in
     * {@link JobState#TERMINATING}.
     */
    private synchronized void onJobFinished() {
        if (state == JobState.TERMINATED) {
            return;
        }
        if (state == JobState.TERMINATING) {
            toTERMINATED();
            return;
        }
        toIDLE();
        if (nextExecutionTime != NO_EXECUTION) {
            long delay = nextExecutionTime - System.currentTimeMillis();
            enqueueWaitingTickerJob(delay);
        }
    }

    /**
     * A wrapper for jobs. After the job finishes, either goes to a {@link JobState#IDLE} or
     * enqueues its own next run {@link JobState#WAITING} (implementation in
     * {@link #onJobFinished()}).
     */
    private class DelayedBackgroundRunnable implements PrioRunnable {
        private final Runnable job;

        DelayedBackgroundRunnable(Runnable job) {
            this.job = job;
        }

        @Override
        public void run() {
            try {
                if (onJobStarted()) {
                    job.run();
                }
            } finally {
                onJobFinished();
            }
        }

        @Override
        public int getPriority() {
            if (job instanceof PrioRunnable) {
                return ((PrioRunnable)job).getPriority();
            }
            return NativeThread.NORM_PRIORITY;
        }
    }

    /**
     * For testing purposes. Returns the internal job state.
     */
    synchronized JobState getState() {
        return state;
    }
}
