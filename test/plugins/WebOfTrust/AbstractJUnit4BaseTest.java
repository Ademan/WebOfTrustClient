/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import plugins.WebOfTrust.Trust.TrustID;
import plugins.WebOfTrust.exceptions.DuplicateTrustException;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import freenet.crypt.DummyRandomSource;
import freenet.crypt.RandomSource;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;

/**
 * The base class for all JUnit4 tests in WOT.<br>
 * Contains utilities useful for all tests such as deterministic random number generation. 
 * 
 * @author xor (xor@freenetproject.org)
 */
@Ignore("Is ignored so it can be abstract. If you need to add self-tests, use member classes, "
    +   "they likely won't be ignored. But then also check that to make sure.")
public abstract class AbstractJUnit4BaseTest {

    protected RandomSource mRandom;
    
    @Rule
    public final TemporaryFolder mTempFolder = new TemporaryFolder();
    
    /** @see #setupUncaughtExceptionHandler() */
    private final AtomicReference<Throwable> uncaughtException
        = new AtomicReference<Throwable>(null);
    
    
    @Before public void setupRandomNumberGenerator() {
        Random seedGenerator = new Random();
        long seed = seedGenerator.nextLong();
        mRandom = new DummyRandomSource(seed);
        System.out.println(this + " Random seed: " + seed);
    }
    
    /**
     * JUnit will by default ignore uncaught Exceptions in threads other than the ones it
     * created itself, so we must register a handler for them to pass them to the main JUnit
     * threads. We pass them by setting {@link #uncaughtException}, and checking its value in
     * {@code @After} {@link #testUncaughtExceptions()}.
     */
    @Before public void setupUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                uncaughtException.compareAndSet(null, e);
            }
        });
    }
    
    /** @see #setupUncaughtExceptionHandler() */
    @After public void testUncaughtExceptions() {
        Throwable t = uncaughtException.get();
        if(t != null)
            fail(t.toString());
    }
    
    /** @see #setupUncaughtExceptionHandler() */
    @Test public void testSetupUncaughtExceptionHandler() throws InterruptedException {
        Thread t = new Thread(new Runnable() {@Override public void run() {
            throw new RuntimeException();
        }});
        t.start();
        t.join();
        assertNotEquals(null, uncaughtException.get());
        // Set back to null so testUncaughtExceptions() does not fail
        uncaughtException.set(null);
    }

    /**
     * Will be used as backend by member functions which generate random
     * {@link Identity} / {@link Trust} / {@link Score} objects. 
     */
    protected abstract WebOfTrustInterface getWebOfTrust();

    /**
     * Returns a new {@link WebOfTrust} instance with an empty database. 
     * Multiple calls to this are guaranteed to use a different database file each.
     */
    protected WebOfTrustInterface constructEmptyWebOfTrust() {
    	try {
    		File dataDir = mTempFolder.newFolder();
    		File database = new File(dataDir, dataDir.getName() + ".db4o");
    		assertFalse(database.exists());
    		return new MockWebOfTrust();
    	} catch(IOException e) {
    		fail(e.toString());
    		throw new RuntimeException(e);
    	}
    }
    
    /**
     * Returns a normally distributed value with a bias towards positive trust values.
     * TODO: Remove this bias once trust computation is equally fast for negative values;
     */
    protected byte getRandomTrustValue() {
        final double trustRange = Trust.MAX_TRUST_VALUE - Trust.MIN_TRUST_VALUE + 1;
        long result;
        do {
            result = Math.round(mRandom.nextGaussian()*(trustRange/2) + (trustRange/3));
        } while(result < Trust.MIN_TRUST_VALUE || result > Trust.MAX_TRUST_VALUE);
        
        return (byte)result;
    }

    /**
     * Generates a random SSK insert URI, for being used when creating {@link OwnIdentity}s.
     */
    protected FreenetURI getRandomInsertURI() {
        return InsertableClientSSK.createRandom(mRandom, "").getInsertURI();
    }

    /**
     * Generates a random SSK request URI, suitable for being used when creating identities.
     */
    protected FreenetURI getRandomRequestURI() {
        return InsertableClientSSK.createRandom(mRandom, "").getURI();
    }
    
    /**
     * Generates a String containing random characters of the lowercase Latin alphabet.
     * @param The length of the returned string.
     */
    protected String getRandomLatinString(int length) {
        char[] s = new char[length];
        for(int i=0; i<length; ++i)
            s[i] = (char)('a' + mRandom.nextInt(26));
        return new String(s);
    }

	/**
	 * Uses reflection to check assertEquals() and assertNotSame() on all member fields of an original and its clone().
	 * Does not check assertNotSame() for:
	 * - enum field
	 * - String fields
	 * - transient fields
	 * 
	 * ATTENTION: Only checks the fields of the given clazz, NOT of its parent class.
	 * If you need to test the fields of an object of class B with parent class A, you should call this two times:
	 * Once with clazz set to A and once for B.
	 * 
	 * @param class The clazz whose fields to check. The given original and clone must be an instance of this or a subclass of it. 
	 * @param original The original object.
	 * @param clone A result of <code>original.clone();</code>
	 */
	protected void testClone(Class<?> clazz, Object original, Object clone) throws IllegalArgumentException, IllegalAccessException {
		for(Field field : clazz.getDeclaredFields()) {
			field.setAccessible(true);
			if(!field.getType().isArray()) {
				assertEquals(field.toGenericString(), field.get(original), field.get(clone));
			} else { // We need to check it deeply if it is an array
				// Its not possible to cast primitive arrays such as byte[] to Object[]
				// Therefore, we must store them as Object which is possible, and then use Array.get()
				final Object originalArray = field.get(original);
				final Object clonedArray = field.get(clone);
				
				assertEquals(Array.getLength(originalArray), Array.getLength(clonedArray));
				for(int i=0; i < Array.getLength(originalArray); ++i) {
					testClone(originalArray.getClass(), Array.get(originalArray, i), Array.get(clonedArray, i));
				}
			}
				
			
			if(!field.getType().isEnum() // Enum objects exist only once
				&& field.getType() != String.class // Strings are interned and therefore might also exist only once
				&& !Modifier.isTransient(field.getModifiers())) // Persistent.mWebOfTurst/mDB are transient field which have the same value everywhere
			{
				final Object originalField = field.get(original);
				final Object clonedField = field.get(clone);
				if(originalField != null)
					assertNotSame(field.toGenericString(), originalField, clonedField);
				else
					assertNull(field.toGenericString(), clonedField); // assertNotSame would fail if both are null because null and null are the same
			}
		}
	}
}
