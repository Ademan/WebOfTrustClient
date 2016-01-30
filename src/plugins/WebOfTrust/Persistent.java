/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.WebOfTrust;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import freenet.support.CurrentTimeUTC;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.Closer;


/**
 * ATTENTION: This class is duplicated in the Freetalk plugin. Backport any changes!
 * 
 * This is the base class for all classes which are stored in the web of trust database.<br /><br />
 * 
 * It provides common functions which are needed for storing, updating, retrieving and deleting objects.
 * 
 * @author xor (xor@freenetproject.org)
 */
public abstract class Persistent implements Serializable {
	// TODO: Optimization: We do explicit activation everywhere. We could change this to 0 and test whether everything still works.
	// Ideally, we would benchmark both 0 and 1 and make it configurable.
	public static transient final int DEFAULT_ACTIVATION_DEPTH = 1;
	
	/** @see Serializable */
	private static transient final long serialVersionUID = 1L;

	/**
	 * A reference to the {@link WebOfTrustInterface} object with which this Persistent object is associated.
	 */
	protected transient WebOfTrustInterface mWebOfTrust;
	
	/**
	 * The date when this persistent object was created. 
	 * - This is contained in class Persistent because it is something which we should store for all persistent objects:
	 * It can be very useful for debugging purposes or sanitizing old databases.
	 * Also it is needed in many cases for the UI.
	 */
	protected Date mCreationDate = CurrentTimeUTC.get();
	
	/**
	 * The object used for locking transactions.
	 * Since we only support one open database at a moment there is only one.
	 */
	private static transient final Object mTransactionLock = new Object();
	
	/* These booleans are used for preventing the construction of log-strings if logging is disabled (for saving some cpu cycles) */
	
	private static transient volatile boolean logDEBUG = false;
	private static transient volatile boolean logMINOR = false;
	
	static {
		Logger.registerClass(Persistent.class);
	}
	
	/**
	 * Get the date when this persistent object was created.
	 * This date is stored in the database so it is constant for a given persistent object.
	 */
	public final Date getCreationDate() {
		return (Date)mCreationDate.clone(); // Date is mutable so we clone it.
	}
	
	/**
	 * ATTENTION: Only use this in clone():
	 * For debugging purposes, the creation Date shall tell clearly when this object was created, it should never change.
	 */
	protected void setCreationDate(final Date creationDate) {
		// checkedDelete(mCreationDate); /* Not stored because db4o considers it as a primitive */
		mCreationDate = (Date)creationDate.clone();
	}
	  
	/**
	 * Returns an unique identifier of this persistent object.
	 * For any given subclass class of Persistent only one object may exist in the database which has a certain ID.
	 * 
	 * The ID must also be unique for subclasses of the subclass:
	 * For example an {@link OwnIdentity} object must not use an ID which is already used by an {@link Identity} object
	 * because Identity is the parent class of OwnIdentity.
	 */
	public abstract String getID();
	
	/**
	 * Uses standard Java serialization to convert this Object to a byte array. NOT used by db4o.
	 * 
	 * The purpose for this is to allow in-db4o storage of cloned {@link Identity}/{@link Trust}/{@link Score}/etc. objects:
	 * Normally there should only be one object with a given ID in the database, if we clone a Persistent object it will have the same ID.
	 * If we store objects as a byte[] instead of using native db4o object storage, we can store those duplcates.
	 * 
	 * Typically used by {@link SubscriptionManager} for being able to store clones.
	 * 
	 * ATTENTION: Your Persistent class must provide an implementation of the following function:
	 * <code>private void writeObject(ObjectOutputStream stream) throws IOException;</code>
	 * This function is not specified by an interface, it can be read up about in the <a href="http://docs.oracle.com/javase/7/docs/platform/serialization/spec/output.html#861">serialization documentation</a>.
	 * It must properly activate the object, all of its members and all of their members:
	 * serialize() will store all members and their members. If they are not activated, this will fail.
	 * After that, it must call {@link ObjectOutputStream#defaultWriteObject()}.
	 * 
	 * @see Persistent#deserialize(WebOfTrustInterface, byte[]) The inverse function.
	 */
	final byte[] serialize() {
		ByteArrayOutputStream bos = null;
		ObjectOutputStream ous = null;
		
		try {
			bos = new ByteArrayOutputStream();
			ous = new ObjectOutputStream(bos);
			ous.writeObject(this);	
			ous.flush();
			return bos.toByteArray();
		} catch(IOException e) {
			throw new RuntimeException(e);
		} finally {
			Closer.close(ous);
			Closer.close(bos);
		}
	}
	
	/** Inverse function of {@link #serialize()}. */
	static final Persistent deserialize(final WebOfTrustInterface wot, final byte[] data) {
		ByteArrayInputStream bis = null;
		ObjectInputStream ois = null;
		
		try {
			bis = new ByteArrayInputStream(data);
			ois = new ObjectInputStream(bis);
			final Persistent deserialized = (Persistent)ois.readObject();
			return deserialized;
		} catch(IOException e) {
			throw new RuntimeException(e);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		} finally {
			Closer.close(ois);
			Closer.close(bis);
		}
	}
	
}
