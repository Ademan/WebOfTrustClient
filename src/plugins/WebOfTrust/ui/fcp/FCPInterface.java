/* This code is part of WoT, a plugin for Freenet. It is distributed
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WebOfTrust.ui.fcp;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import plugins.WebOfTrust.EventSource;
import plugins.WebOfTrust.Identity;
import plugins.WebOfTrust.Identity.IdentityID;
import plugins.WebOfTrust.OwnIdentity;
import plugins.WebOfTrust.Score;
import plugins.WebOfTrust.SubscriptionManager;
import plugins.WebOfTrust.SubscriptionManager.UnknownSubscriptionException;
import plugins.WebOfTrust.Trust;
import plugins.WebOfTrust.exceptions.InvalidParameterException;
import plugins.WebOfTrust.exceptions.NoSuchContextException;
import plugins.WebOfTrust.exceptions.NotInTrustTreeException;
import plugins.WebOfTrust.exceptions.NotTrustedException;
import plugins.WebOfTrust.exceptions.UnknownIdentityException;
import plugins.WebOfTrust.exceptions.UnknownPuzzleException;
import plugins.WebOfTrust.ui.fcp.FCPClientReferenceImplementation.SubscriptionType;
import plugins.WebOfTrust.util.RandomName;
import freenet.clients.fcp.FCPPluginConnection;
import freenet.clients.fcp.FCPPluginMessage;
import freenet.keys.FreenetURI;
import freenet.node.FSParseException;
import freenet.pluginmanager.FredPluginFCP;
import freenet.pluginmanager.FredPluginFCPMessageHandler;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginReplySender;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Base64;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

public final class FCPInterface {
    /**
     * Thrown if delivery of a message to the client succeeded but the client indicated that the
     * processing of the message did not succeed (via {@link FCPPluginMessage#success} == false).
     * <br>In opposite to {@link IOException}, which should result in assuming the connection to the
     * client to be closed, this may be used to trigger re-sending of a certain message over the
     * same connection.
     */
    public static final class FCPCallFailedException extends Exception {
        private static final long serialVersionUID = 1L;
        
        public FCPCallFailedException(FCPPluginMessage clientReply) {
            super("The client indicated failure of processing the message."
                + " errorCode: " + clientReply.errorCode
                + "; errorMessage: " + clientReply.errorMessage);
            
            assert(clientReply.success == false);
        }
    }

}
