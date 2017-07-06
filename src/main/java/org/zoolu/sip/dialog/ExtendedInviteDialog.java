/*
 * Copyright (C) 2005 Luca Veltri - University of Parma - Italy
 *
 * This file is part of MjSip (http://www.mjsip.org)
 *
 * MjSip is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * MjSip is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MjSip; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 * Author(s):
 * Luca Veltri (luca.veltri@unipr.it)
 */

package org.zoolu.sip.dialog;

import java.util.Hashtable;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zoolu.sip.address.NameAddress;
import org.zoolu.sip.authentication.DigestAuthentication;
import org.zoolu.sip.header.AuthorizationHeader;
import org.zoolu.sip.header.RequestLine;
import org.zoolu.sip.header.StatusLine;
import org.zoolu.sip.header.ViaHeader;
import org.zoolu.sip.header.WwwAuthenticateHeader;
import org.zoolu.sip.message.Message;
import org.zoolu.sip.message.MessageFactory;
import org.zoolu.sip.message.SipMethods;
import org.zoolu.sip.message.SipResponses;
import org.zoolu.sip.provider.SipProvider;
import org.zoolu.sip.provider.TransactionIdentifier;
import org.zoolu.sip.transaction.AckTransactionClient;
import org.zoolu.sip.transaction.Transaction;
import org.zoolu.sip.transaction.TransactionClient;
import org.zoolu.sip.transaction.TransactionServer;

/**
 * Class ExtendedInviteDialog can be used to manage extended invite dialogs.
 * <p>
 * An ExtendedInviteDialog allows the user: <br>
 * - to handle authentication <br>
 * - to handle refer/notify <br>
 * - to capture all methods within the dialog
 */
public class ExtendedInviteDialog extends org.zoolu.sip.dialog.InviteDialog {
	private static Logger log = LoggerFactory.getLogger(ExtendedInviteDialog.class);
	/** Max number of registration attempts. */
	static final int MAX_ATTEMPTS = 3;

	/** ExtendedInviteDialog listener. */
	ExtendedInviteDialogListener dialog_listener;

	/** Acive transactions. */
	Map<TransactionIdentifier, Transaction> transactions;

	/** User name. */
	String username;

	/** User name. */
	String realm;

	/** User's passwd. */
	String passwd;

	/** Nonce for the next authentication. */
	String next_nonce;

	/** Qop for the next authentication. */
	String qop;

	/** Number of authentication attempts. */
	int attempts;

	/** Creates a new ExtendedInviteDialog. */
	public ExtendedInviteDialog(SipProvider provider, ExtendedInviteDialogListener listener) {
		super(provider, listener);
		init(listener);
	}

	/** Creates a new ExtendedInviteDialog. */
	public ExtendedInviteDialog(SipProvider provider, String username, String realm, String passwd,
			ExtendedInviteDialogListener listener) {
		super(provider, listener);
		init(listener);
		this.username = username;
		this.realm = realm;
		this.passwd = passwd;
	}

	/** Inits the ExtendedInviteDialog. */
	private void init(ExtendedInviteDialogListener listener) {
		this.dialog_listener = listener;
		this.transactions = new Hashtable<>();
		this.username = null;
		this.realm = null;
		this.passwd = null;
		this.next_nonce = null;
		this.qop = null;
		this.attempts = 0;
	}

	/** Sends a new request within the dialog */
	public void request(Message req) {
		TransactionClient t = new TransactionClient(sip_provider, req, this);
		transactions.put(t.getTransactionId(), t);
		t.request();
	}

	/** Sends a new REFER within the dialog */
	public void refer(NameAddress refer_to) {
		refer(refer_to, null);
	}

	/** Sends a new REFER within the dialog */
	public void refer(NameAddress refer_to, NameAddress referred_by) {
		Message req = MessageFactory.createReferRequest(this, refer_to, referred_by);
		request(req);
	}

	/** Sends a new NOTIFY within the dialog */
	public void notify(int code, String reason) {
		notify((new StatusLine(code, reason)).toString());
	}

	/** Sends a new NOTIFY within the dialog */
	public void notify(String sipfragment) {
		Message req = MessageFactory.createNotifyRequest(this, "refer", null, sipfragment);
		request(req);
	}

	/** Responds with <i>resp</i> */
	@Override
	public void respond(Message resp) {
		log.debug("inside respond(resp)");
		String method = resp.getCSeqHeader().getMethod();
		if (method.equals(SipMethods.INVITE) || method.equals(SipMethods.CANCEL) || method.equals(SipMethods.BYE)) {
			super.respond(resp);
		} else {
			TransactionIdentifier transaction_id = resp.getTransactionId();
			log.debug("transaction-id=" + transaction_id);
			if (transactions.containsKey(transaction_id)) {
				log.trace("responding");
				TransactionServer t = (TransactionServer) transactions.get(transaction_id);
				t.respondWith(resp);
			} else
				log.debug("transaction server not found; message discarded");
		}
	}

	/** Accept a REFER */
	public void acceptRefer(Message req) {
		log.debug("inside acceptRefer(refer)");
		Message resp = MessageFactory.createResponse(req, 202, SipResponses.reasonOf(200), null);
		respond(resp);
	}

	/** Refuse a REFER */
	public void refuseRefer(Message req) {
		log.debug("inside refuseRefer(refer)");
		Message resp = MessageFactory.createResponse(req, 603, SipResponses.reasonOf(603), null);
		respond(resp);
	}

	/** Inherited from class SipProviderListener. */
	@Override
	public void onReceivedMessage(SipProvider provider, Message msg) {
		log.trace("Message received: " + msg.getFirstLine().substring(0, msg.toString().indexOf('\r')));
		if (msg.isResponse()) {
			super.onReceivedMessage(provider, msg);
		} else if (msg.isInvite() || msg.isAck() || msg.isCancel() || msg.isBye()) {
			super.onReceivedMessage(provider, msg);
		} else {
			TransactionServer t = new TransactionServer(sip_provider, msg, this);
			transactions.put(t.getTransactionId(), t);
			// t.listen();

			if (msg.isRefer()) { // Message resp=MessageFactory.createResponse(msg,202,"Accepted",null,null);
									// respond(resp);
				NameAddress refer_to = msg.getReferToHeader().getNameAddress();
				NameAddress referred_by = null;
				if (msg.hasReferredByHeader())
					referred_by = msg.getReferredByHeader().getNameAddress();
				dialog_listener.onDlgRefer(this, refer_to, referred_by, msg);
			} else if (msg.isNotify()) {
				Message resp = MessageFactory.createResponse(msg, 200, SipResponses.reasonOf(200), null);
				respond(resp);
				String event = msg.getEventHeader().getValue();
				String sipfragment = msg.getBody();
				dialog_listener.onDlgNotify(this, event, sipfragment, msg);
			} else {
				log.debug("Received alternative request " + msg.getRequestLine().getMethod());
				dialog_listener.onDlgAltRequest(this, msg.getRequestLine().getMethod(), msg.getBody(), msg);
			}
		}
	}

	/**
	 * Inherited from TransactionClientListener. When the TransactionClientListener goes into the "Completed" state,
	 * receiving a failure response
	 */
	@Override
	public void onTransFailureResponse(TransactionClient tc, Message msg) {
		log.warn("onTransFailureResponse 1");
		log.trace("inside onTransFailureResponse(" + tc.getTransactionId() + ",msg)");
		String method = tc.getTransactionMethod();
		StatusLine status_line = msg.getStatusLine();
		int code = status_line.getCode();
		String reason = status_line.getReason();

		// AUTHENTICATION-BEGIN
		if ((code == 401 && attempts < MAX_ATTEMPTS && msg.hasWwwAuthenticateHeader() && msg.getWwwAuthenticateHeader()
				.getRealmParam().equalsIgnoreCase(realm))
				|| (code == 407 && attempts < MAX_ATTEMPTS && msg.hasProxyAuthenticateHeader() && msg
						.getProxyAuthenticateHeader().getRealmParam().equalsIgnoreCase(realm))) {
			attempts++;

			Message ack = MessageFactory.createRequest(this, SipMethods.ACK, null);
			AckTransactionClient acktc = new AckTransactionClient(sip_provider, ack, null);
			// TransactionClient acktc=new TransactionClient(sip_provider,ack,this);
			// transactions.put(acktc.getTransactionId(),acktc);
			acktc.request();

			Message req = tc.getRequestMessage();
			// select a new branch - rfc3261 says should be new on each request
			ViaHeader via = req.getViaHeader();
			req.removeViaHeader();
			via.setBranch(SipProvider.pickBranch());
			req.addViaHeader(via);
			req.setCSeqHeader(req.getCSeqHeader().incSequenceNumber());
			WwwAuthenticateHeader wah;
			if (code == 401)
				wah = msg.getWwwAuthenticateHeader();
			else
				wah = msg.getProxyAuthenticateHeader();
			String qop_options = wah.getQopOptionsParam();
			qop = (qop_options != null) ? "auth" : null;
			RequestLine rl = req.getRequestLine();
			DigestAuthentication digest = new DigestAuthentication(rl.getMethod(), rl.getAddress().toString(), wah,
					qop, null, username, passwd);
			AuthorizationHeader ah;
			if (code == 401)
				ah = digest.getAuthorizationHeader();
			else
				ah = digest.getProxyAuthorizationHeader();
			req.setAuthorizationHeader(ah);

			if (req.isInvite()) // make sure it's an invite
				this.invite_req = req; // must track last invite so cancel will work correctly - fixes 503 from asterisk
										// on cancel

			transactions.remove(tc.getTransactionId());
			tc = new TransactionClient(sip_provider, req, this);
			transactions.put(tc.getTransactionId(), tc);
			tc.request();
		} else
		// AUTHENTICATION-END
		if (method.equals(SipMethods.INVITE) || method.equals(SipMethods.CANCEL) || method.equals(SipMethods.BYE)) {
			super.onTransFailureResponse(tc, msg);
		} else if (tc.getTransactionMethod().equals(SipMethods.REFER)) {
			transactions.remove(tc.getTransactionId());
			dialog_listener.onDlgReferResponse(this, code, reason, msg);
		} else {
			String body = msg.getBody();
			transactions.remove(tc.getTransactionId());
			dialog_listener.onDlgAltResponse(this, method, code, reason, body, msg);
		}
	}

	/**
	 * Inherited from TransactionClientListener. When an TransactionClientListener goes into the "Terminated" state,
	 * receiving a 2xx response
	 */
	@Override
	public void onTransSuccessResponse(TransactionClient t, Message msg) {
		log.trace("inside onTransSuccessResponse(" + t.getTransactionId() + ",msg)");
		attempts = 0;
		String method = t.getTransactionMethod();
		StatusLine status_line = msg.getStatusLine();
		int code = status_line.getCode();
		String reason = status_line.getReason();

		if (method.equals(SipMethods.INVITE) || method.equals(SipMethods.CANCEL) || method.equals(SipMethods.BYE)) {
			super.onTransSuccessResponse(t, msg);
		} else if (t.getTransactionMethod().equals(SipMethods.REFER)) {
			transactions.remove(t.getTransactionId());
			dialog_listener.onDlgReferResponse(this, code, reason, msg);
		} else {
			String body = msg.getBody();
			transactions.remove(t.getTransactionId());
			dialog_listener.onDlgAltResponse(this, method, code, reason, body, msg);
		}
	}

	/**
	 * Inherited from TransactionClientListener. When the TransactionClient goes into the "Terminated" state, caused by
	 * transaction timeout
	 */
	@Override
	public void onTransTimeout(TransactionClient t) {
		log.trace("inside onTransTimeout(" + t.getTransactionId() + ",msg)");
		String method = t.getTransactionMethod();
		if (method.equals(SipMethods.INVITE) || method.equals(SipMethods.BYE)) {
			super.onTransTimeout(t);
		} else { // do something..
			transactions.remove(t.getTransactionId());
		}
	}
}
