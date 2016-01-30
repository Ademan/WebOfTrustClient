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
	
	@SuppressWarnings("serial")
	public static final class Client extends Persistent {

		/**
		 * The way of notifying a client
		 */
		public static enum Type {
			FCP,
			Callback /** Not implemented yet. */
		};
		
		/**
		 * The way the client desires notification.
		 * 
		 * @see #getType()
		 */
		private final Type mType;
		
		/**
		 * An ID which associates this client with a FCP connection if the type is FCP.<br><br>
		 * 
		 * Must be a valid {@link UUID}, see {@link PluginRespirator#getPluginConnectionByID(UUID)}.
		 * <br> (Stored as String so it is a db4o native type and doesn't require explicit
		 * management). 
		 * 
		 * @see #getFCP_ID()
		 */
		private final String mFCP_ID;

		/**
		 * Each {@link Notification} is given an index upon creation. The indexes ensure sequential processing.
		 * The indexed queue exists per {@link Client} and not per {@link Subscription}:
		 * Events of different types of {@link Subscription} might be dependent upon each other. 
		 * For example if we want to notify a client about a new trust value via {@link TrustChangedNotification}, it doesn't make
		 * sense to deploy such a notification if the identity which created the trust value does not exist yet.
		 * It must be guaranteed that the {@link IdentityChangedNotification} which creates the identity is deployed first.
		 * Events are issued by the core of WOT in proper order, so as long as we keep a queue per Client which preserves
		 * this order everything will be fine.
		 */
		private long mNextNotificationIndex = 0;
		
		/**
		 * If deploying the {@link Notification} queue fails, for example due to connectivity issues, this is incremented.
		 * After a retry limit of {@link SubscriptionManager#DISCONNECT_CLIENT_AFTER_FAILURE_COUNT}, the client will be disconnected.
		 */
		private byte mSendNotificationsFailureCount = 0;
		
		/** @param myFCP_ID See {@link #mFCP_ID} */
		public Client(final UUID myFCP_ID) {
            assert(myFCP_ID != null);
            
			mType = Type.FCP;
			mFCP_ID = myFCP_ID.toString();
		}
		
		/**
		 * @throws UnsupportedOperationException Always because it is not implemented.
		 */
		@Override
		public final String getID() {
			throw new UnsupportedOperationException("Not implemented.");
		}
		
		/**
		 * You must call {@link #initializeTransient} before using this!
		 */
		protected final SubscriptionManager getSubscriptionManager() {
			return mWebOfTrust.getSubscriptionManager();
		}
				
		/**
		 * @return The {@link Type} of this Client.
		 * @see #mType
		 */
		public final Type getType() {
			return mType;
		}
		
		/**
		 * @return An ID which associates this Client with a FCP connection if the type is FCP.
		 * @see #mFCP_ID
		 */
		public final UUID getFCP_ID() {
			if(getType() != Type.FCP)
				throw new UnsupportedOperationException("Type is not FCP:" + getType());
			
			return UUID.fromString(mFCP_ID);
		}
		
		/**
		 * Returns the next free index for a {@link Notification} in the queue of this Client.
		 * 
		 * Stores this Client object without committing the transaction.
		 * Schedules processing of the Notifications of the SubscriptionManger via {@link SubscriptionManager#scheduleNotificationProcessing()}.
		 */
		protected final long takeFreeNotificationIndexWithoutCommit() {
			final long index = mNextNotificationIndex++;
			getSubscriptionManager().scheduleNotificationProcessing();
			return index;
		}
		
		/**
		 * @see #mSendNotificationsFailureCount
		 */
		public final byte getSendNotificationsFailureCount() {
			return mSendNotificationsFailureCount;
		}
		
		/**
		 * Increments {@link #mSendNotificationsFailureCount} and returns the new value.
		 * Use this for disconnecting a client if {@link #sendNotifications(SubscriptionManager)} has failed too many times.
		 * 
		 * @return The value of {@link #mSendNotificationsFailureCount} after incrementing it.
		 */
		private final byte incrementSendNotificationsFailureCountWithoutCommit()  {
			++mSendNotificationsFailureCount;
			return mSendNotificationsFailureCount;
		}

		@Override
		public String toString() {
			return super.toString() + " { Type=" + getType() + "; FCP ID=" + getFCP_ID() + " }"; 
		}
	}
	
	/**
	 * A subscription stores the information which client is subscribed to which content.<br>
	 * For each {@link Client}, one subscription is stored one per {@link EventSource}-type.
	 * A {@link Client} cannot have multiple subscriptions of the same type.
	 * 
	 * Notice: Even though this is an abstract class, it contains code specific <b>all</>b> types of subscription clients such as FCP and callback.
	 * At first glance, this looks like a violation of abstraction principles. But it is not:
	 * Subclasses of this class shall NOT be created for different types of clients such as FCP and callbacks.
	 * Subclasses are created for different types of EventSource to which the subscriber is
	 * subscribed: There is a subclass for subscriptions to the list of {@link Identity}s, the list
	 * of {@link Trust}s, and so on. Each subclass has to implement the code for notifying
	 * <b>all</b> types of clients (FCP, callback, etc.).
	 * Therefore, this base class also contains code for <b>all</b> kinds of clients.
	 */
	@SuppressWarnings("serial")
	public static abstract class Subscription<EventType extends EventSource> extends Persistent {
		
		/**
		 * The {@link Client} which created this {@link Subscription}.
		 */
		private final Client mClient;
		
		/**
		 * The UUID of this Subscription. Stored as String for db4o performance, but must be valid in terms of the UUID class.
		 * 
		 * @see #getID()
		 */
		private final String mID;
		
		/**
		 * Constructor for being used by child classes.
		 * @param myClient The {@link Client} to which this Subscription belongs.
		 */
		protected Subscription(final Client myClient) {
			mClient = myClient;
			mID = UUID.randomUUID().toString();
			
			assert(mClient != null);
		}

		/**
		 * Gets the {@link Client} which created this {@link Subscription}
		 * @see #mClient
		 */
		protected final Client getClient() {
			return mClient;
		}
		
		/**
		 * @return The UUID of this Subscription. Stored as String for db4o performance, but must be valid in terms of the UUID class.
		 * @see #mID
		 */
		@Override
		public final String getID() {
			return mID;
		}
		
		/**
		 * Must return all objects of a given EventType which form a valid synchronization.<br>
		 * This is all objects of the EventType stored in the {@link WebOfTrust}.<br><br>
		 * 
         * <b>Thread safety:</b><br>
         * This must be called while locking upon the {@link WebOfTrust}.<br>
         * Therefore it may perform database queries on the WebOfTrust to obtain the dataset.<br>
         * 
         * @see #storeSynchronizationWithoutCommit()
         *         storeSynchronizationWithoutCommit() will use this function to obtain the dataset
         *         of this function. Its JavaDoc also explains what a "synchronization" is in more
         *         detail.
		 */
		abstract List<EventType> getSynchronization();

		@Override
		public String toString() {
			return super.toString() + " { ID=" + getID() + "; Client=" + getClient() + " }";
		}
	}
	
	/**
	 * An object of type Notification is stored when an event happens to which a client is possibly subscribed.
	 * The SubscriptionManager will wake up some time after that, pull all notifications from the database and process them.
	 */
	@SuppressWarnings("serial")
	public static abstract class Notification extends Persistent {
		
		/**
		 * The {@link Client} to which this Notification belongs
		 */
		private final Client mClient;
		
		/**
		 * The {@link Subscription} to which this Notification belongs
		 */
		private final Subscription<? extends EventSource> mSubscription;
		
		/**
		 * The index of this Notification in the queue of its {@link Client}:
		 * Notifications are supposed to be sent out in proper sequence, therefore we use incremental indices.
		 */
		private final long mIndex;
	
        /**
         * Constructs a Notification in the queue of the given Client.<br>
         * Takes a free Notification index from it with
         * {@link Client#takeFreeNotificationIndexWithoutCommit}.
         * 
         * @param mySubscription The {@link Subscription} which requested this type of Notification.
         */
        Notification(final Subscription<? extends EventSource> mySubscription) {
            mSubscription = mySubscription;
            mClient = mSubscription.getClient();
            mIndex = mClient.takeFreeNotificationIndexWithoutCommit();
        }

        /**
         * @deprecated Not implemented because we don't need it.
         */
        @Override
        @Deprecated()
        public String getID() {
            throw new UnsupportedOperationException();
        }
        
        /**
         * @return The {@link Subscription} which requested this type of Notification.
         */
        public Subscription<? extends EventSource> getSubscription() {
            return mSubscription;
        }
	}
	
	/**
     * It provides two clones of the {@link Persistent} object about whose change the client shall be notified:
     * - A version of it before the change via {@link ObjectChangedNotification#getOldObject()}<br>
     * - A version of it after the change via {@link ObjectChangedNotification#getNewObject()}<br>
     * 
     * If one of the before/after getters returns null, this is because the object was added/deleted.
     * If both do return an non-null object, the object was modified.
     * NOTICE: Modification can also mean that its class has changed!
     * 
     * NOTICE: Both Persistent objects are not stored in the database and must not be stored there to prevent duplicates!
     * <br><br>
     * 
     * ATTENTION: {@link ObjectChangedNotification#getOldObject()}==null does NOT mean that the
     * object did not exist before the notification for ObjectChangedNotifications which are
     * deployed as part of a {@link Subscription} synchronization.<br>
     * See {@link Subscription#storeSynchronizationWithoutCommit()} and
     * {@link BeginSynchronizationNotification}.
	 */
	@SuppressWarnings("serial")
	public static abstract class ObjectChangedNotification extends Notification {
		
		/**
		 * A serialized copy of the changed {@link Persistent} object before the change.
		 * Null if the change was the creation of the object.
		 * If non-null its {@link Persistent#getID()} must be equal to the one of {@link #mNewObject} if that member is non-null as well.
		 * 
		 * @see Persistent#serialize()
		 * @see #getOldObject() The public getter for this.
		 */
		private final byte[] mOldObject;
		
		/**
		 * A serialized copy of the changed {@link Persistent} object after the change.
		 * Null if the change was the deletion of the object.
		 * If non-null its {@link Persistent#getID()} must be equal to the one of {@link #mOldObject} if that member is non-null as well.
		 * 
		 * @see Persistent#serialize()
		 * @see #getNewObject() The public getter for this.
		 */
		private final byte[] mNewObject;
		
		/**
		 * Only one of oldObject or newObject may be null.
		 * If both are non-null, their {@link Persistent#getID()} must be equal.
		 * 
		 * TODO: Code quality: oldObject and newObject should be EventSource, not Persistent.
		 * 
		 * @param mySubscription The {@link Subscription} which requested this type of Notification.
		 * @param oldObject The version of the changed {@link Persistent} object before the change.
		 * @param newObject The version of the changed {@link Persistent} object after the change.
		 * @see Notification#Notification(Subscription) This parent constructor is also called.
		 */
		ObjectChangedNotification(final Subscription<? extends EventSource> mySubscription,
		        final Persistent oldObject, final Persistent newObject) {
		    
			super(mySubscription);
			
			assert	(
						(oldObject == null ^ newObject == null) ||
						(oldObject != null && newObject != null && oldObject.getID().equals(newObject.getID()))
					);
			
			mOldObject = (oldObject != null ? oldObject.serialize() : null);
			mNewObject = (newObject != null ? newObject.serialize() : null);
		}

		/**
		 * Returns the changed {@link Persistent} object before the change.<br>
		 * Null if the change was the creation of the object.<br><br>
		 * 
		 * ATTENTION: A return value of null does NOT mean that the object did not exist before the
		 * notification for ObjectChangedNotifications which are deployed as part of a
		 * {@link Subscription} synchronization.<br>
		 * See {@link Subscription#storeSynchronizationWithoutCommit()} and
		 * {@link BeginSynchronizationNotification}.
		 * 
		 * @see #mOldObject The backend member variable of this getter.
		 */
		public final Persistent getOldObject() throws NoSuchElementException {
			return mOldObject != null ? Persistent.deserialize(mWebOfTrust, mOldObject) : null;
		}
		
		/**
		 * @return The changed {@link Persistent} object after the change. Null if the change was the deletion of the object.
		 * @see #mNewObject The backend member variable of this getter.
		 */
		public final Persistent getNewObject() throws NoSuchElementException {
			return mNewObject != null ? Persistent.deserialize(mWebOfTrust, mNewObject) : null;
		}

		@Override
		public String toString() {
			return super.toString() + " { oldObject=" + getOldObject() + "; newObject=" + getNewObject() + " }";
		}
	}
	
	/**
	 * Shall mark the begin of a series of synchronization {@link ObjectChangedNotification}s. See
	 * {@link Subscription#storeSynchronizationWithoutCommit()} for a description what
	 * "synchronization" means here.<br><br>
	 * 
	 * All {@link ObjectChangedNotification}s following this marker notification shall be considered
	 * as part of the synchronization, up to the end marker of type
	 * {@link EndSynchronizationNotification}.<br><br>
	 * 
	 * Attention: The {@link EndSynchronizationNotification} is a child class of this, not a
	 * different class. Make sure to avoid accidentally matching it by 
	 * "instanceof BeginSynchronizationNotification".<br>
	 * TODO: Code quality: The only reason for the above ambiguity is to eliminate code duplication
	 * because the End* class needs some functions from Begin*. Resolve the ambiguity by adding a
	 * third class AbstractSynchronizationNotification as parent class for both to contain the
	 * common code; or by having a single class which contains a boolean which tells whether its
	 * begin or end.
	 */
	@SuppressWarnings("serial")
    public static class BeginSynchronizationNotification<EventType extends EventSource>
	        extends Notification {
        /**
         * All {@link EventSource} objects which are stored inside of
         * {@link ObjectChangedNotification} as part of the synchronization which is marked by this
         * {@link BeginSynchronizationNotification} shall be bound to this ID by calling
         * {@link EventSource#setVersionID(UUID)}.<br>
         * This allows the client to use a "mark-and-sweep" garbage collection mechanism to delete
         * obsolete {@link EventSource} objects which existed in its database before the
         * synchronization: After having received the end-marker
         * {@link EndSynchronizationNotification}, any object of type EventType whose
         * {@link EventSource#getVersionID(UUID)} does not match the version ID of the current
         * synchronization is an obsolete object and must be deleted.<br><br>
         * 
         * (The {@link UUID} is stored as {@link String} for simplifying usage of db4o: Strings are
         * native objects and thus do not have to be manually deleted.)
         */
	    private final String mVersionID;
	    
	    
        BeginSynchronizationNotification(Subscription<EventType> mySubscription) {
            super(mySubscription);
            mVersionID = UUID.randomUUID().toString();
        }
        
        /** Only for being used by {@link EndSynchronizationNotification}. */
        BeginSynchronizationNotification(Subscription<EventType> mySubscription, String versionID) {
            super(mySubscription);
            mVersionID = versionID;
        }

        /** @see #mVersionID */
        @Override public String getID() {
            return mVersionID;
        }
        
        @Override
        public String toString() {
            return super.toString() + " { mVersionID=" + getID() + " }";
        }
	}

	/**
	 * @see BeginSynchronizationNotification
	 */
    @SuppressWarnings("serial")
    public static class EndSynchronizationNotification<EventType extends EventSource>
            extends BeginSynchronizationNotification<EventType> {
        
        @SuppressWarnings("unchecked")
        EndSynchronizationNotification(BeginSynchronizationNotification<EventType> begin) {
            super((Subscription<EventType>) begin.getSubscription(), begin.getID());
            
            assert !(begin instanceof EndSynchronizationNotification);
        }
    }
	
	/**
	 * This notification is issued when an {@link Identity} is added/deleted or its attributes change.
	 * 
	 * It provides a {@link Identity#clone()} of the identity:
	 * - before the change via {@link Notification#getOldObject()}
	 * - and and after the change via ({@link Notification#getNewObject()}
	 * 
	 * If one of the before/after getters returns null, this is because the identity was added/deleted.
	 * If both return an identity, the identity was modified.
	 * NOTICE: Modification can also mean that its class changed from {@link OwnIdentity} to {@link Identity} or vice versa!
	 * 
	 * NOTICE: Both Identity objects are not stored in the database and must not be stored there to prevent duplicates!
	 * 
	 * @see IdentitiesSubscription The type of {@link Subscription} which deploys this notification.
	 */
	@SuppressWarnings("serial")
	public static class IdentityChangedNotification extends ObjectChangedNotification {
		/**
		 * Only one of oldIentity and newIdentity may be null. If both are non-null, their {@link Identity#getID()} must match.
		 * 
		 * @param mySubscription The {@link Subscription} to whose {@link Notification} queue this {@link Notification} belongs.
		 * @param oldIdentity The version of the {@link Identity} before the change.
		 * @param newIdentity The version of the {@link Identity} after the change.
		 */
		protected IdentityChangedNotification(final Subscription<Identity> mySubscription, 
				final Identity oldIdentity, final Identity newIdentity) {
			super(mySubscription, oldIdentity, newIdentity);
		}

	}
	
	/**
	 * This notification is issued when a {@link Trust} is added/deleted or its attributes change.
	 * 
	 * It provides a {@link Trust#clone()} of the trust:
	 * - before the change via {@link Notification#getOldObject()}
	 * - and and after the change via ({@link Notification#getNewObject()}
	 * 
	 * If one of the before/after getters returns null, this is because the trust was added/deleted.
	 * 
	 * NOTICE: Both Trust objects are not stored in the database and must not be stored there to prevent duplicates!
	 * 
	 * @see TrustsSubscription The type of {@link Subscription} which deploys this notification.
	 */
	@SuppressWarnings("serial")
	public static final class TrustChangedNotification extends ObjectChangedNotification {
		/**
		 * Only one of oldTrust and newTrust may be null. If both are non-null, their {@link Trust#getID()} must match.
		 * 
		 * @param mySubscription The {@link Subscription} to whose {@link Notification} queue this {@link Notification} belongs.
		 * @param oldTrust The version of the {@link Trust} before the change.
		 * @param newTrust The version of the {@link Trust} after the change.
		 */
		protected TrustChangedNotification(final Subscription<Trust> mySubscription, 
				final Trust oldTrust, final Trust newTrust) {
			super(mySubscription, oldTrust, newTrust);
		}
		
	}
	
	/**
	 * This notification is issued when a {@link Score} is added/deleted or its attributes change.
	 * 
	 * It provides a {@link Score#clone()} of the score:
	 * - before the change via {@link Notification#getOldObject()}
	 * - and and after the change via ({@link Notification#getNewObject()}
	 * 
	 * If one of the before/after getters returns null, this is because the score was added/deleted.
	 * 
	 * NOTICE: Both Score objects are not stored in the database and must not be stored there to prevent duplicates!
	 * 
	 * @see ScoresSubscription The type of {@link Subscription} which deploys this notification.
	 */
	@SuppressWarnings("serial")
	public static final class ScoreChangedNotification extends ObjectChangedNotification {
		/**
		 * Only one of oldScore and newScore may be null. If both are non-null, their {@link Score#getID()} must match.
		 * 
		 * @param mySubscription The {@link Subscription} to whose {@link Notification} queue this {@link Notification} belongs.
		 * @param oldScore The version of the {@link Score} before the change.
		 * @param newScore The version of the {@link Score} after the change.
		 */
		protected ScoreChangedNotification(final Subscription<Score> mySubscription,
				final Score oldScore, final Score newScore) {
			super(mySubscription, oldScore, newScore);
		}

	}

	/**
	 * A subscription to the set of all {@link Identity} and {@link OwnIdentity} instances.
	 * If an identity gets added/deleted or if its attributes change the subscriber is notified by a {@link IdentityChangedNotification}.
	 * 
	 * @see IdentityChangedNotification The type of {@link Notification} which is deployed by this subscription.
	 */
	@SuppressWarnings("serial")
	public static final class IdentitiesSubscription extends Subscription<Identity> {

		/**
		 * @param myClient The {@link Client} which created this Subscription. 
		 */
		protected IdentitiesSubscription(final Client myClient) {
			super(myClient);
		}


		/** {@inheritDoc} */
        @Override List<Identity> getSynchronization() {
            return mWebOfTrust.getAllIdentities();
        }

	}
	
	/**
	 * A subscription to the set of all {@link Trust} instances.
	 * If a trust gets added/deleted or if its attributes change the subscriber is notified by a {@link TrustChangedNotification}.
	 * 
	 * @see TrustChangedNotification The type of {@link Notification} which is deployed by this subscription.
	 */
	@SuppressWarnings("serial")
	public static final class TrustsSubscription extends Subscription<Trust> {

		/**
		 * @param myClient The {@link Client} which created this Subscription. 
		 */
		protected TrustsSubscription(final Client myClient) {
			super(myClient);
		}

        /** {@inheritDoc} */
        @Override List<Trust> getSynchronization() {
            return mWebOfTrust.getAllTrusts();
        }

	}
	
	/**
	 * A subscription to the set of all {@link Score} instances.
	 * If a score gets added/deleted or if its attributes change the subscriber is notified by a {@link ScoreChangedNotification}.
	 * 
	 * @see ScoreChangedNotification The type of {@link Notification} which is deployed by this subscription.
	 */
	@SuppressWarnings("serial")
	public static final class ScoresSubscription extends Subscription<Score> {

		/**
		 * @param myClient The {@link Client} which created this Subscription.
		 */
		protected ScoresSubscription(final Client myClient) {
			super(myClient);
		}

        /** {@inheritDoc} */
        @Override List<Score> getSynchronization() {
            return mWebOfTrust.getAllScores();
        }
	}

	
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
	 * Thrown when a single {@link Client} tries to file a {@link Subscription} of the same class of
	 * {@link EventSource}.
	 * 
	 * TODO: Performance: Do not generate a stack trace, as this is a planned Exception.
	 * 
	 * @see #throwIfSimilarSubscriptionExists
	 */
	@SuppressWarnings("serial")
	public static final class SubscriptionExistsAlreadyException extends Exception {
		public final Subscription<? extends EventSource> existingSubscription;
		
		public SubscriptionExistsAlreadyException(
		        Subscription<? extends EventSource> existingSubscription) {
		    
			this.existingSubscription = existingSubscription;
		}
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
