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

package org.zoolu.sip.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zoolu.sip.message.Message;
import org.zoolu.sip.provider.ConnectionIdentifier;
import org.zoolu.sip.provider.SipProvider;
import org.zoolu.sip.provider.SipProviderListener;
import org.zoolu.sip.provider.TransactionIdentifier;
import org.zoolu.tools.Timer;
import org.zoolu.tools.TimerListener;

/**
 * Abstract class Transaction is hinerited by classes ClientTransaction,
 * ServerTransaction, InviteClientTransaction and InviteServerTransaction. An
 * Object Transaction is responsable to handle a new SIP transaction.<BR>
 * The changes of the internal status and the received messages are fired to the
 * TransactionListener passed to the Transaction Objects.<BR>
 */
public abstract class Transaction implements SipProviderListener, TimerListener {
	protected static Logger log = LoggerFactory.getLogger(Transaction.class);
	/** Transactions counter */
	protected static int transaction_counter = 0;

	// all transaction states:
	/** State Waiting, used only by server transactions */
	static final int STATE_IDLE = 0;
	/** State Waiting, used only by server transactions */
	static final int STATE_WAITING = 1;
	/** State Trying */
	static final int STATE_TRYING = 2;
	/** State Proceeding */
	static final int STATE_PROCEEDING = 3;
	/** State Completed */
	static final int STATE_COMPLETED = 4;
	/** State Confirmed, used only by invite server transactions */
	static final int STATE_CONFIRMED = 5;
	/** State Waiting. */
	static final int STATE_TERMINATED = 7;

	/** Gets the transaction state. */
	static String getStatus(int st) {
		switch (st) {
			case STATE_IDLE:
				return "T_Idle";
			case STATE_WAITING:
				return "T_Waiting";
			case STATE_TRYING:
				return "T_Trying";
			case STATE_PROCEEDING:
				return "T_Proceeding";
			case STATE_COMPLETED:
				return "T_Completed";
			case STATE_CONFIRMED:
				return "T_Confirmed";
			case STATE_TERMINATED:
				return "T_Terminated";
			default:
				return null;
		}
	}

	/** Transaction sequence number */
	int transaction_sqn;

	/**
	 * Lower layer dispatcher that sends and receive messages. The messages received
	 * by the SipProvider are fired to the Transaction by means of the
	 * onReceivedMessage() method.
	 */
	SipProvider sip_provider;

	/** Internal state-machine status */
	int status;

	/** transaction request message/method */
	Message request;

	/** the Transaction ID */
	TransactionIdentifier transaction_id;

	/** Transaction connection id */
	ConnectionIdentifier connection_id;

	/** Costructs a new Transaction */
	protected Transaction(SipProvider sip_provider) {
		this.sip_provider = sip_provider;
		this.transaction_id = null;
		this.request = null;
		this.connection_id = null;
		this.transaction_sqn = transaction_counter++;
		this.status = STATE_IDLE;
	}

	/** Changes the internal status */
	void changeStatus(int newstatus) {
		status = newstatus;
		// transaction_listener.onChangedTransactionStatus(status);
		log.debug("changed transaction state: " + getStatus());
	}

	/** Whether the internal status is equal to <i>st</i> */
	boolean statusIs(int st) {
		return status == st;
	}

	/** Gets the current transaction state. */
	String getStatus() {
		return getStatus(status);
	}

	/** Gets the SipProvider of this Transaction. */
	public SipProvider getSipProvider() {
		return sip_provider;
	}

	/** Gets the Transaction request message */
	public Message getRequestMessage() {
		return request;
	}

	/** Gets the Transaction method */
	public String getTransactionMethod() {
		return request.getTransactionMethod();
	}

	/** Gets the transaction-ID */
	public TransactionIdentifier getTransactionId() {
		return transaction_id;
	}

	/** Gets the transaction connection id */
	public ConnectionIdentifier getConnectionId() {
		return connection_id;
	}

	/**
	 * Method derived from interface SipListener. It's fired from the SipProvider
	 * when a new message is catch for to the present ServerTransaction.
	 */
	public void onReceivedMessage(SipProvider provider, Message msg) { // do nothing
	}

	/**
	 * Method derived from interface TimerListener. It's fired from an active Timer.
	 */
	public void onTimeout(Timer to) { // do nothing
	}

	/** Terminates the transaction. */
	public abstract void terminate();
}
