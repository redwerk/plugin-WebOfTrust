/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.exceptions;

/**
 * Thrown when querying the Trust between two identities
 * that don't trust each other.
 * 
 * @author Julien Cornuwel (batosai@freenetproject.org)
 */
public class NotTrustedException extends Exception {
	
	private static final long serialVersionUID = -1;

	public NotTrustedException(String message) {
		super(message);
	}
}
