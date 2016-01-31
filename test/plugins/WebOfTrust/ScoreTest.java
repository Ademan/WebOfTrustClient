/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import java.net.MalformedURLException;

import org.junit.Before;
import static org.junit.Assert.*;

import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import freenet.support.CurrentTimeUTC;

/**
 * @author xor (xor@freenetproject.org)
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class ScoreTest extends AbstractJUnit4BaseTest {
	
	private String requestUriA = "USK@Pn5K9Lt4pE0v5I3TDF40yPkDeE6IJP-nZ~zxxEq76Yc,t3vIf26txb~g6yP1f5cANe1Cb98uzcQBqCAG1PO03OQ,AQACAAE/WebOfTrust/0";
	private String insertUriA = "USK@f3bEbhW5xmevbzAE2sfAsioNQezrKeak6vUYWhHAoLk,t3vIf26txb~g6yP1f5cANe1Cb98uzcQBqCAG1PO03OQ,AQECAAE/WebOfTrust/0";
	private String requestUriB = "USK@a6dD~md7InpruJ3B98RiqRwPJ9L3w~N6l5Ad14neUVU,WZkyt7jgLFJLnVpQ7q7vWBCkz8t8O9JbU1Qsg9bLvdo,AQACAAE/WebOfTrust/0";
	private String insertUriB = "USK@CSkCsPeEqkRNbO~xtEpL~gMHzEydwwP6ofJBMMArZX4,WZkyt7jgLFJLnVpQ7q7vWBCkz8t8O9JbU1Qsg9bLvdo,AQECAAE/WebOfTrust/0";
	private OwnIdentity a;
	private OwnIdentity b;
	private Score score;

	@Before
	public void setUp() throws Exception {
		a = new OwnIdentity(insertUriA, "A", true);
		b = new OwnIdentity(insertUriB, "B", true);
		
		score = new Score(a,b,100,1,40);
	}
	
	public void testClone() throws NotInTrustTreeException, IllegalArgumentException, IllegalAccessException, InterruptedException {
		final Score original = score;
		
		Thread.sleep(10); // Score contains Date mLastChangedDate which might not get properly cloned.
		assertFalse(CurrentTimeUTC.get().equals(original.getDateOfLastChange()));
		
		final Score clone = original.clone();
		
		assertEquals(original, clone);
		assertNotSame(original, clone);
		
		testClone(Persistent.class, original, clone);
		testClone(Score.class, original, clone);
	}
	
	public void testSerializeDeserialize() throws NotInTrustTreeException {
		final Score original = score;
		final Score deserialized = (Score)Persistent.deserialize(original.serialize());
		
		assertNotSame(original, deserialized);
		assertEquals(original, deserialized);
		
		assertNotSame(original.getTruster(), deserialized.getTruster());
		assertEquals(original.getTruster(), deserialized.getTruster());	// Score.equals() only checks the ID
		
		assertNotSame(original.getTrustee(), deserialized.getTrustee());
		assertEquals(original.getTrustee(), deserialized.getTrustee());	// Score.equals() only checks the ID
	}

	public void testScoreCreation() throws NotInTrustTreeException {
		final Score original = score;
		
		assertTrue(score.getScore() == 100);
		assertTrue(score.getRank() == 1);
		assertTrue(score.getCapacity() == 40);
		assertTrue(score.getTruster() == a);
		assertTrue(score.getTrustee() == b);
	}
	
	public void testEquals() throws InterruptedException {
		final Score score = new Score(a, b, 100, 3, 2);
		
		do {
			Thread.sleep(1);
		} while(score.getDateOfCreation().equals(CurrentTimeUTC.get()));
		
		final Score equalScore = new Score(score.getTruster().clone(), score.getTrustee().clone(), score.getScore(), score.getRank(), score.getCapacity());
		
		assertEquals(score, score);
		assertEquals(score, equalScore);
		
		
		final Object[] inequalObjects = new Object[] {
			new Object(),
			new Score((OwnIdentity)score.getTrustee(), score.getTruster(), score.getScore(), score.getRank(), score.getCapacity()),
			new Score(score.getTruster(), score.getTruster(), score.getScore(), score.getRank(), score.getCapacity()),
			new Score((OwnIdentity)score.getTrustee(), score.getTrustee(), score.getScore(), score.getRank(), score.getCapacity()),
			new Score(score.getTruster(), score.getTrustee(), score.getScore()+1, score.getRank(), score.getCapacity()),
			new Score(score.getTruster(), score.getTrustee(), score.getScore(), score.getRank()+1, score.getCapacity()),
			new Score(score.getTruster(), score.getTrustee(), score.getScore(), score.getRank(), score.getCapacity()+1),
		};
		
		for(Object other : inequalObjects) {
			assertFalse(score.equals(other));
		}
	}
}
