/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.WebOfTrust;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import plugins.WebOfTrust.exceptions.DuplicateObjectException;
import plugins.WebOfTrust.ui.fcp.FCPInterface.FCPCallFailedException;
import plugins.WebOfTrust.util.jobs.BackgroundJob;
import plugins.WebOfTrust.util.jobs.DelayedBackgroundJob;
import plugins.WebOfTrust.util.jobs.MockDelayedBackgroundJob;
import plugins.WebOfTrust.util.jobs.TickerDelayedBackgroundJob;

import freenet.node.PrioRunnable;
import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.PooledExecutor;
import freenet.support.PrioritizedTicker;
import freenet.support.Ticker;
import freenet.support.codeshortification.IfNull;
import freenet.support.io.NativeThread;

/**
 * The subscription manager allows client application to subscribe to certain data sets of WoT and get notified on change.
 * For example, if you subscribe to the list of identities, you will get a notification when an identity is added or removed.
 * 
 * The architecture of this class supports implementing different types of subscriptions: Currently, only FCP is implemented, but it is also technically possible to have subscriptions
 * which do a callback within the WoT plugin or maybe even via OSGI.
 * 
 * The class/object model is as following:
 * - There is exactly one SubscriptionManager object running in the WOT plugin. It is the interface for {@link Client}s.
 * - Subscribing to something yields a {@link Subscription} object which is stored by the SubscriptionManager in the database. Clients do not need to keep track of it. They only need to know its ID.
 * - When an event happens, a {@link Notification} object is created for each {@link Subscription} which matches the type of event. The Notification is stored in the database.
 * - After a delay, the SubscriptionManager deploys the notifications to the clients.
 * 
 * The {@link Notification}s are deployed strictly sequential per {@link Client}.
 * If a single Notification cannot be deployed, the processing of the Notifications for that Client is halted until the failed Notification can
 * be deployed successfully. There will be {@link #DISCONNECT_CLIENT_AFTER_FAILURE_COUNT} retries, then the Client is disconnected.
 * 
 * Further, at each deployment run, the order of deployment is guaranteed to "make sense":
 * A {@link TrustChangedNotification} which creates a {@link Trust} will not deployed before the {@link IdentityChangedNotification} which creates
 * the identities which are referenced by the trust.
 * This allows you to assume that any identity IDs (see {@link Identity#getID()}} you receive in trust / score notifications are valid when you receive them.
 * 
 * This is a very important principle which makes client design easy: You do not need transaction-safety when caching things such as score values
 * incrementally. For example your client might need to do mandatory actions due to a score-value change, such as deleting messages from identities
 * which have a bad score now. If the score-value import succeeds but the message deletion fails, you can just return "ERROR!" to the WOT-callback-caller
 * (and maybe even keep your score-cache as is) - you will continue to receive the notification about the changed score value for which the import failed,
 * you will not receive change-notifications after that. This ensures that your consistency is not destroyed: There will be no missing slot
 * in the incremental change chain.
 * 
 * <b>Synchronization:</b>
 * The locking order must be:
 * 	synchronized(instance of WebOfTrust) {
 *	synchronized(instance of IntroductionPuzzleStore) {
 *	synchronized(instance of IdentityFetcher) {
 *	synchronized(instance of SubscriptionManager) {
 *	synchronized(Persistent.transactionLock(instance of ObjectContainer)) {
 * This does not mean that you need to take all of those locks when calling functions of the SubscriptionManager:
 * Its just the general order of locks which is used all over Web Of Trust to prevent deadlocks.
 * Any functions which require synchronization upon some of the locks will mention it.
 * 
 * TODO: Allow out-of-order notifications if the client desires them
 * TODO: Optimization: Allow coalescing of notifications: If a single object changes twice, only send one notification
 * TODO: Optimization: Allow the client to specify filters to reduce traffic: - Context of identities, etc. 
 * 
 * 
 * TODO: This should be used for powering the IntroductionClient/IntroductionServer.
 * 
 * @author xor (xor@freenetproject.org)
 */
public final class SubscriptionManager implements PrioRunnable {
	/**
	 * After a {@link Notification} command is stored, we wait this amount of time before processing it.
	 * This is to allow some coalescing when multiple notifications happen in a short interval.
	 * This is usually the case as the import of trust lists often causes multiple changes.
	 * 
	 * Further, if deploying a {@link Notification} fails and its resend-counter is not exhausted, it will be resent after this delay.
	 */
	public static final long PROCESS_NOTIFICATIONS_DELAY = 60 * 1000;
	
	/**
	 * If {@link Client#sendNotifications(SubscriptionManager)} fails, the failure counter of the subscription is incremented.
	 * If the counter reaches this value, the client is disconnected.
	 */
	public static final byte DISCONNECT_CLIENT_AFTER_FAILURE_COUNT = 5;
	
	
	/**
	 * The {@link WebOfTrust} to which this SubscriptionManager belongs.
	 */
	private final WebOfTrustInterface mWoT;

	/**
	 * The SubscriptionManager schedules execution of its notification deployment thread on this
	 * {@link DelayedBackgroundJob}.<br>
	 * The execution typically is scheduled after a delay of {@link #PROCESS_NOTIFICATIONS_DELAY}.
	 * <br><br>
	 * 
     * The value distinguishes the run state of this SubscriptionManager as follows:<br>
     * - Until {@link #start()} was called, defaults to {@link MockDelayedBackgroundJob#DEFAULT}
     *   with {@link DelayedBackgroundJob#isTerminated()} == true.<br>
     * - Once {@link #start()} has been called, becomes a
     *   {@link TickerDelayedBackgroundJob} with {@link DelayedBackgroundJob#isTerminated()}
     *   == false.<br>
     * - Once {@link #stop()} has been called, stays a {@link TickerDelayedBackgroundJob} but has
     *   {@link DelayedBackgroundJob#isTerminated()} == true for ever.<br><br>
     * 
     * There can be exactly one start() - stop() lifecycle, a SubscriptionManager cannot be
     * recycled.<br><br>
     * 
     * Volatile since {@link #stop()} needs to use it without synchronization.
	 */
    private volatile DelayedBackgroundJob mJob = MockDelayedBackgroundJob.DEFAULT;


	/** Automatically set to true by {@link Logger} if the log level is set to {@link LogLevel#DEBUG} for this class.
	 * Used as performance optimization to prevent construction of the log strings if it is not necessary. */
	private static transient volatile boolean logDEBUG = false;
	
	/** Automatically set to true by {@link Logger} if the log level is set to {@link LogLevel#MINOR} for this class.
	 * Used as performance optimization to prevent construction of the log strings if it is not necessary. */
	private static transient volatile boolean logMINOR = false;
	
	static {
		// Necessary for automatic setting of logDEBUG and logMINOR
		Logger.registerClass(SubscriptionManager.class);
	}
	
	/**
	 * Constructor both for regular in-node operation as well as operation in unit tests.
	 * 
	 * @param myWoT The {@link WebOfTrust} to which this SubscriptionManager belongs. Its {@link WebOfTrust#getPluginRespirator()} may return null in unit tests.
	 */
	public SubscriptionManager(WebOfTrustInterface myWoT) {
		mWoT = myWoT;
	}
	
	/**
	 * Thrown by various functions which query the database for a certain {@link Client} if none exists matching the given filters.
	 * TODO: Performance: Look at the throwers and see whether this Exception is predictable enough
	 * to justify not generating a stack trace.
	 */
	@SuppressWarnings("serial")
	public static final class UnknownClientException extends Exception {
		
		/**
		 * @param message A description of the filters which were set in the database query for the {@link Client}.
		 */
		public UnknownClientException(String message) {
			super(message);
		}
	}
	
	/**
	 * Thrown by various functions which query the database for a certain {@link Subscription} if none exists matching the given filters.
	 * TODO: Performance: Look at the throwers and see whether this Exception is predictable enough
	 * to justify not generating a stack trace.
	 */
	@SuppressWarnings("serial")
	public static final class UnknownSubscriptionException extends Exception {
		
		/**
		 * @param message A description of the filters which were set in the database query for the {@link Subscription}.
		 */
		public UnknownSubscriptionException(String message) {
			super(message);
		}
	}

	/**
	 * Sends out the {@link Notification} queue of each {@link Client}.
	 * 
	 * Typically called by the DelayedBackgroundJob {@link #mJob} on a separate thread. This is
	 * triggered by {@link #scheduleNotificationProcessing()} - that scheduling function should be
	 * called whenever a {@link Notification} is stored to the database. <br>
	 * {@link Thread#interrupt()} may be called by {@link DelayedBackgroundJob#terminate()} to
	 * request the thread to exit soon for speeding up shutdown.<br><br>
	 * 
	 * If deploying the notifications for a {@link Client} fails, this function is scheduled to be run again after some time.
	 * If deploying for a certain {@link Client} fails more than {@link #DISCONNECT_CLIENT_AFTER_FAILURE_COUNT} times, the {@link Client} is deleted.
	 * 
	 * @see Client#sendNotifications(SubscriptionManager) This function is called on each {@link Client} to deploy the {@link Notification} queue.
	 */
	@Override
	public void run() {}
	
	/** {@inheritDoc} */
	@Override
	public int getPriority() {
		return NativeThread.LOW_PRIORITY;
	}
	
	/**
	 * Schedules the {@link #run()} method to be executed after a delay of {@link #PROCESS_NOTIFICATIONS_DELAY}
	 */
	private void scheduleNotificationProcessing() {
        // We do not do the following commented out assert() because:
        // 1) WebOfTrust.upgradeDB() can rightfully cause this function to be called before start()
        //    (in case it calls functions which create Notifications):
        //    upgradeDB()'s job is to update outdated databases to a new database format. No
        //    subsystem of WOT which accesses the database should be start()ed before the database
        //    has been converted to the current format, including the SubscriptionManager.
        // 2) It doesn't matter if we don't process Notifications which were queued before start():
        //    start() will automatically delete all existing Clients, so the Notifications would be
        //    deleted as well.
        //
        /* assert(mJob != MockDelayedBackgroundJob.DEFAULT)
            : "Should not be called before start() as mJob won't execute then!"; */
        
        // We do not do this because some unit tests intentionally stop() us before they run.
        /*  assert (!mJob.isTerminated()) : "Should not be called after stop()!"; */
        
        mJob.triggerExecution();
	}
	

	/**
	 * Deletes all old {@link Client}s, {@link Subscription}s and {@link Notification}s and enables subscription processing. 
	 * Enables usage of {@link #scheduleNotificationProcessing()()}.
	 * 
	 * You must call this before any subscriptions are created, so for example before FCP is available.
	 * 
	 * ATTENTION: Does NOT work in unit tests - you must manually trigger subscription processing by calling {@link #run()} there.
	 * 
	 * TODO: Code quality: start() and {@link #stop()} are partly duplicated in class
	 * IdentityFetcher. It might thus make sense to add basic functionality of start() / stop()
	 * to {@link BackgroundJob}. This might for example include the ability to specify pre-startup
	 * and pre-shutdown callbacks which the {@link BackgroundJob} calls upon us so we can do
	 * our own initialization / cleanup.
	 */
	protected synchronized void start() {
		Logger.normal(this, "start()...");

        // This is thread-safe guard against concurrent multiple calls to start() / stop() since
        // stop() does not modify the job and start() is synchronized. 
        if(mJob != MockDelayedBackgroundJob.DEFAULT)
            throw new IllegalStateException("start() was already called!");
        
        // This must be called while synchronized on this SubscriptionManager, and the lock must be
        // held until mJob is set (which we do by having the whole function be synchronized):
        // Holding the lock prevents Clients or Notifications from being created before
        // scheduleNotificationProcessing() is made functioning by setting mJob.
        // It is critically necessary for scheduleNotificationProcessing() to be working before
        // any Clients/Notifications can be created: Notifications to Clients will only be sent out
        // if scheduleNotificationProcssing() is functioning at the moment a Notification is
        // created.
        // Notice: Once you change Clients/Notifications to be persistent across restarts of WOT,
        // and therefore remove this, please make sure to notice and update the comments inside
        // scheduleNotificationProcessing().
		
		final PluginRespirator respirator = mWoT.getPluginRespirator();
        final Ticker ticker;
        final Runnable jobRunnable;
        
		if(respirator != null) { // We are connected to a node
            ticker = respirator.getNode().getTicker();
            jobRunnable = this;
		} else { // We are inside of a unit test
		    Logger.warning(this, "No PluginRespirator available, will never run job. "
		                       + "This should only happen in unit tests!");
            
            // Generate our own Ticker so we can set mJob to be a real TickerDelayedBackgroundJob.
            // This is better than leaving it be a MockDelayedBackgroundJob because it allows us to
            // clearly distinguish the run state (start() not called, start() called, stop() called)
            ticker = new PrioritizedTicker(new PooledExecutor(), 0);
            jobRunnable = new Runnable() { @Override public void run() {
                 // Do nothing because:
                 // - We shouldn't do work on custom executors, we should only ever use the main
                 //   one of the node.
                 // - Unit tests execute instantly after loading the WOT plugin, so delayed jobs
                 //   should not happen since their timing cannot be guaranteed to match the unit
                 //   tests execution state.
               };
            };
		}
		
        // Set the volatile mJob after all of startup is finished to ensure that stop() can use it
        // *without* synchronization to check whether start() was called already.
        mJob = new TickerDelayedBackgroundJob(
            jobRunnable, "WoT SubscriptionManager", PROCESS_NOTIFICATIONS_DELAY, ticker);
		
		Logger.normal(this, "start() finished.");
	}
	
	/**
	 * Shuts down this SubscriptionManager by aborting all queued notification processing and waiting for running processing to finish.
	 * 
	 * Notice: Not synchronized so it can be run in parallel with {@link #run()}. This will allow it
	 * to call {@link DelayedBackgroundJob#terminate()} while run() is executing, which calls
	 * {@link Thread#interrupt()} on the run()-thread to cause it to exit quickly.
	 */
	protected void stop() {
		Logger.normal(this, "stop()...");
		
        // The following code intentionally does NOT write to the mJob variable so it does not have
        // to use synchronized(this). We do not want to synchronize because:
        // 1) run() is synchronized(this), so we would not get the lock until run() is finished.
        //    But we want to call mJob.terminate() immediately while run() is still executing to
        //    make it call Thread.interrupt() upon run() to speed up its termination. So we
        //    shouldn't require acquisition of the lock before terminate().
        // 2) Keeping mJob as is makes sure that start() is not possible anymore so this object can
        //    only have a single lifecycle. Recycling being impossible reduces complexity and is not
        //    needed for normal operation of WOT anyway.
        
        
        // Since mJob can only transition from not "not started yet" as implied by the "==" here
        // to "started" as implied by "!=", but never backwards, and is set by start() after
        // everything is completed, this is thread-safe against concurrent start() / stop().
        if(mJob == MockDelayedBackgroundJob.DEFAULT)
            throw new IllegalStateException("start() not called yet!");
        
        // We cannot guard against concurrent stop() here since we don't synchronize, we can only
        // probabilistically detect it by assert(). Concurrent stop() is not a problem though since
        // restarting jobs is not possible: We cannot run into a situation where we accidentally
        // stop the wrong lifecycle. It can only happen that we do cleanup the cleanup which a
        // different thread would have done, but they won't care since all used functions below will
        // succeed silently if called multiple times.
        assert !mJob.isTerminated() : "stop() called already";
        
        mJob.terminate();
		try {
		    // TODO: Performance: Decrease if it doesn't interfere with plugin unloading. I would
		    // rather not though: Plugin unloading unloads the JAR of the plugin, and thus all its
		    // classes. That will probably cause havoc if threads of it are still running.
            mJob.waitForTermination(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            // We are a shutdown function, there is no sense in sending a shutdown signal to us.
            Logger.error(this, "stop() should not be interrupt()ed.", e);
        }

		Logger.normal(this, "stop() finished.");
	}
}
