/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;

import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import freenet.crypt.RandomSource;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.node.NodeStarter.TestNodeParameters;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger.LogLevel;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.PooledExecutor;

/**
 * A base class for WOT unit tests.<br>
 * As opposed to regular WOT unit tests based upon {@link AbstractJUnit3BaseTest}, this test runs the
 * unit tests inside a full Freenet node:<br>
 * WOT is loaded as a regular plugin instead of executing the tests directly without Freenet.<br>
 * <br>
 * 
 * This has the advantage of allowing more complex tests:<br>
 * - The {@link PluginRespirator} is available<br>
 * - FCP can be used.<br><br>
 * 
 * The price is that it is much more heavy to initialize and thus has a higher execution time.<br>
 * Thus, please only use it as a base class if what {@link AbstractJUnit3BaseTest} provides is not
 * sufficient.<br>
 * 
 * @author xor (xor@freenetproject.org
 */
@Ignore("Is ignored so it can be abstract. If you need to add self-tests, use member classes, "
    +   "they likely won't be ignored. But then also check that to make sure.")
public abstract class AbstractFullNodeTest
        extends AbstractJUnit4BaseTest {
    
    /** Needed for calling {@link NodeStarter#globalTestInit(File, boolean, LogLevel, String,
     *  boolean, RandomSource) only once per VM as it requires that. */
    private static boolean sGlobalTestInitDone = false;
}
