/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;

import static org.junit.Assert.*;
import org.junit.Before;

import freenet.support.CurrentTimeUTC;

import plugins.WebOfTrust.exceptions.DuplicateTrustException;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;

/**
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class TrustTest extends AbstractJUnit4BaseTest {
	
	private String uriA = "USK@MF2Vc6FRgeFMZJ0s2l9hOop87EYWAydUZakJzL0OfV8,fQeN-RMQZsUrDha2LCJWOMFk1-EiXZxfTnBT8NEgY00,AQACAAE/WoT/0";
	private String uriB = "USK@R3Lp2s4jdX-3Q96c0A9530qg7JsvA9vi2K0hwY9wG-4,ipkgYftRpo0StBlYkJUawZhg~SO29NZIINseUtBhEfE,AQACAAE/WoT/0";
	private Identity a;
	private Identity b;
	private Trust trust;

	@Before
	public void setUp() throws Exception {
		a = new Identity(uriA, "A", true);
		b = new Identity(uriB, "B", true);
		
		trust = new Trust(a,b,(byte)100,"test");
	}
	
	public void testClone() throws DuplicateTrustException, NotTrustedException, IllegalArgumentException, IllegalAccessException, InterruptedException {
		final Trust original = trust.clone();
		
		Thread.sleep(10); // Trust contains Date mLastChangedDate which might not get properly cloned.
		assertFalse(CurrentTimeUTC.get().equals(original.getDateOfLastChange()));
		
		final Trust clone = original.clone();
		
		assertEquals(original, clone);
		assertNotSame(original, clone);
		
		testClone(Persistent.class, original, clone);
		testClone(Trust.class, original, clone);
	}
	
	public void testConstructor() throws InvalidParameterException {		
		try {
			new Trust(a, null, (byte)100, "test");
			fail("Constructor allows trustee to be null");
		}
		catch(NullPointerException e) { }
		
		try {
			new Trust(null, a, (byte)100, "test");
			fail("Constructor allows truster to be null");
		}
		catch(NullPointerException e) {}
		
		try {
			new Trust(a, b, (byte)-101, "test");
			fail("Constructor allows values less than -100");
		}
		catch(InvalidParameterException e) {}
		
		try {
			new Trust(a, b, (byte)101, "test");
			fail("Constructor allows values higher than 100");
		}
		catch(InvalidParameterException e) {}
		
		try { 
			new Trust(a, a, (byte)100, "test");
			fail("Constructor allows self-referential trust values");
		}
		catch(InvalidParameterException e) { }
	}
	
	public void testSerializeDeserialize() throws DuplicateTrustException, NotTrustedException {
		final Trust original = trust.clone();
		final Trust deserialized = (Trust)Persistent.deserialize(original.serialize());
		
		assertNotSame(original, deserialized);
		assertEquals(original, deserialized);
		
		assertNotSame(original.getTruster(), deserialized.getTruster());
		assertEquals(original.getTruster(), deserialized.getTruster());	// Trust.equals() only checks the ID
		
		assertNotSame(original.getTrustee(), deserialized.getTrustee());
		assertEquals(original.getTrustee(), deserialized.getTrustee());	// Trust.equals() only checks the ID
	}

	public void testTrust() throws DuplicateTrustException, NotTrustedException {
		final Trust original = trust.clone();
		assertTrue(trust.getTruster() == a);
		assertTrue(trust.getTrustee() == b);
		assertTrue(trust.getValue() == 100);
		assertEquals("test", trust.getComment());
	}
}
